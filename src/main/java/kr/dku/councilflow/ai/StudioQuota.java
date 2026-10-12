package kr.dku.councilflow.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.net.http.HttpHeaders;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.dku.councilflow.job.WorkerProperties;

/** 짧은 행 잠금으로 모든 Worker의 예산을 예약한다. HTTP·sleep은 트랜잭션 밖에서 수행한다. */
@Component
public class StudioQuota {
    private static final Logger log=LoggerFactory.getLogger(StudioQuota.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StudioQuotaProperties properties;
    private final WorkerProperties worker;
    private final Clock clock;
    private record State(Instant until,int requests,long tokens,Instant cooldown) {}

    public StudioQuota(JdbcTemplate jdbc,PlatformTransactionManager manager,StudioQuotaProperties properties,
                       WorkerProperties worker,Clock clock) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.properties=properties;this.worker=worker;this.clock=clock;
    }

    /** null이면 예약 성공, 그 외에는 아직 호출하지 말고 반환된 시각까지 기다린다. */
    public Instant reserve(int tokens,Instant now) {
        int maxTokens=(int)(properties.tokensPerMinute()*properties.budgetRatio());
        if(tokens<0 || tokens>maxTokens) throw new StudioException(StudioException.Kind.PERMANENT,"AI_REQUEST_EXCEEDS_QUOTA");
        return tx.execute(status->{
            initialize(now);
            State s=locked();
            if(s.cooldown()!=null && s.cooldown().isAfter(now)) return s.cooldown();
            if(!s.until().isAfter(now)) s=new State(now.plusSeconds(61),0,0,s.cooldown());
            if(s.requests()+1>(int)(properties.requestsPerMinute()*properties.budgetRatio()) || s.tokens()+tokens>maxTokens) {
                persist(s);return s.until();
            }
            persist(new State(s.until(),s.requests()+1,s.tokens()+tokens,s.cooldown()));return null;
        });
    }

    public void await(int tokens) {
        Instant deadline=clock.instant().plus(properties.maxWait());
        Instant announced=null;
        long waitedNanos=0;
        while(true) {
            Instant now=clock.instant();Instant until=reserve(tokens,now);
            if(until==null) {leaseAndNotice(null);log.info("studio quota reserved tokens={} waited_ms={}",tokens,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(waitedNanos));return;}
            if(!now.isBefore(deadline)) throw new StudioException(StudioException.Kind.PERMANENT,"AI_QUOTA_WAIT_TIMEOUT");
            leaseAndNotice(until);
            if(!until.equals(announced)) {log.info("studio quota wait tokens={} until={}",tokens,until);announced=until;}
            long sleepStarted=System.nanoTime();
            try {Thread.sleep(Math.max(1,Math.min(1000,Duration.between(now,until).toMillis())));}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new StudioException(StudioException.Kind.UNKNOWN_OUTCOME,"AI_INTERRUPTED");}
            finally {waitedNanos+=System.nanoTime()-sleepStarted;}
        }
    }

    public void cooldown(Instant until) {
        tx.executeWithoutResult(status->{initialize(clock.instant());State s=locked();
            Instant cooldown=s.cooldown()==null || s.cooldown().isBefore(until)?until:s.cooldown();
            persist(new State(s.until(),s.requests(),s.tokens(),cooldown));});
    }

    /** 429는 공유 냉각을 적용한다. 성공 시에도 다른 경로에서 소비된 잔량을 보수적으로 반영한다. */
    public void feedback(HttpHeaders headers,boolean limited) {
        Instant now=clock.instant();long reset=Math.max(seconds(headers,"x-ratelimit-reset-requests",0),seconds(headers,"x-ratelimit-reset-tokens",0));
        long requests=number(headers,"x-ratelimit-remaining-requests",-1),tokens=number(headers,"x-ratelimit-remaining-tokens",-1);
        if(requests>=0 || tokens>=0) log.info("studio quota feedback limit_requests={} limit_tokens={} remaining_requests={} remaining_tokens={} reset_sec={} limited={}",
                number(headers,"x-ratelimit-limit-requests",-1),number(headers,"x-ratelimit-limit-tokens",-1),requests,tokens,reset,limited);
        if(limited) {long seconds=Math.max(61,Math.max(reset+1,seconds(headers,"retry-after",0)));cooldown(now.plusSeconds(seconds));log.warn("studio quota cooldown limited=true seconds={}",seconds);return;}
        if(requests<0 && tokens<0) return;
        tx.executeWithoutResult(status->{initialize(now);State s=locked();
            Instant until=reset>0 && now.plusSeconds(reset+1).isAfter(s.until())?now.plusSeconds(reset+1):s.until();
            persist(new State(until,(int)Math.max(s.requests(),requests<0?0:properties.requestsPerMinute()-requests),
                    Math.max(s.tokens(),tokens<0?0:properties.tokensPerMinute()-tokens),s.cooldown()));});
    }

    private void initialize(Instant now) {
        jdbc.update("INSERT IGNORE INTO studio_quota(scope,window_until,used_requests,used_tokens) VALUES(?,?,0,0)",properties.scope(),Timestamp.from(now));
    }
    private State locked() {
        return jdbc.queryForObject("SELECT * FROM studio_quota WHERE scope=? FOR UPDATE",(r,n)->new State(r.getTimestamp("window_until").toInstant(),r.getInt("used_requests"),r.getLong("used_tokens"),r.getTimestamp("cooldown_until")==null?null:r.getTimestamp("cooldown_until").toInstant()),properties.scope());
    }
    private void persist(State s) {
        jdbc.update("UPDATE studio_quota SET window_until=?,used_requests=?,used_tokens=?,cooldown_until=? WHERE scope=?",
                Timestamp.from(s.until()),s.requests(),s.tokens(),s.cooldown()==null?null:Timestamp.from(s.cooldown()),properties.scope());
    }
    private void leaseAndNotice(Instant until) {
        String id=MDC.get("jobId"),version=MDC.get("jobVersion");if(id==null || version==null) return;
        // 진행 표시와 임대 갱신은 도메인 version을 올리지 않고, 소유권 version으로 보호한다.
        int changed=jdbc.update("UPDATE job SET locked_until=?,quota_wait_until=? WHERE id=? AND version=? AND status='RUNNING'",
                Timestamp.from(clock.instant().plus(worker.lease())),until==null?null:Timestamp.from(until),Long.parseLong(id),Long.parseLong(version));
        if(changed!=1) throw new StudioException(StudioException.Kind.UNKNOWN_OUTCOME,"AI_LEASE_LOST");
    }
    private static long number(HttpHeaders h,String key,long fallback) {
        try{return Math.max(0,Long.parseLong(h.firstValue(key).orElseThrow()));}catch(RuntimeException e){return fallback;}
    }
    private static long seconds(HttpHeaders h,String key,long fallback) {
        String raw=h.firstValue(key).orElse("");var match=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m)?").matcher(raw);
        if(!match.matches()) {
            if("retry-after".equals(key)) try {return Math.max(0,Math.min(86400,Duration.between(Instant.now(),java.time.ZonedDateTime.parse(raw,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()));}catch(RuntimeException ignored) {}
            return fallback;
        }
        double n=Double.parseDouble(match.group(1));String unit=match.group(2);
        return Math.min(86400,(long)Math.ceil("ms".equals(unit)?n/1000:"m".equals(unit)?n*60:n));
    }
}
