package org.apereo.cas.web.support;

import org.apereo.cas.util.DateTimeUtils;

import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of a HandlerInterceptorAdapter that keeps track of a mapping
 * of IP Addresses to number of failures to authenticate.
 * <p>
 * Note, this class relies on an external method for decrementing the counts
 * (i.e. a Quartz Job) and runs independent of the threshold of the parent.
 *
 * @author Scott Battaglia
 * @since 3.0.0
 */
public abstract class AbstractInMemoryThrottledSubmissionHandlerInterceptorAdapter
                extends AbstractThrottledSubmissionHandlerInterceptorAdapter implements Job {

    private static final double SUBMISSION_RATE_DIVIDEND = 1000.0;

    @Value("${cas.throttle.inmemory.cleaner.repeatinterval:5000}")
    private int refreshInterval;

    @Value("${cas.throttle.inmemory.cleaner.startdelay:5000}")
    private int startDelay;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    @Qualifier("scheduler")
    private Scheduler scheduler;

    private ConcurrentMap<String, ZonedDateTime> ipMap = new ConcurrentHashMap<>();

    @Override
    protected boolean exceedsThreshold(final HttpServletRequest request) {
        final ZonedDateTime last = this.ipMap.get(constructKey(request));
        return last != null && (submissionRate(ZonedDateTime.now(ZoneOffset.UTC), last) > getThresholdRate());
    }

    @Override
    protected void recordSubmissionFailure(final HttpServletRequest request) {
        this.ipMap.put(constructKey(request), ZonedDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Construct key to be used by the throttling agent to track requests.
     *
     * @param request the request
     * @return the string
     */
    protected abstract String constructKey(HttpServletRequest request);

    /**
     * This class relies on an external configuration to clean it up. It ignores the threshold data in the parent class.
     */
    public void decrementCounts() {
        final Set<Entry<String, ZonedDateTime>> keys = this.ipMap.entrySet();
        logger.debug("Decrementing counts for throttler.  Starting key count: {}", keys.size());

        final ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        for (final Iterator<Entry<String, ZonedDateTime>> iter = keys.iterator(); iter.hasNext();) {
            final Entry<String, ZonedDateTime> entry = iter.next();
            if (submissionRate(now, entry.getValue()) < getThresholdRate()) {
                logger.trace("Removing entry for key {}", entry.getKey());
                iter.remove();
            }
        }
        logger.debug("Done decrementing count for throttler.");
    }

    /**
     * Computes the instantaneous rate in between two given dates corresponding to two submissions.
     *
     * @param a First date.
     * @param b Second date.
     *
     * @return  Instantaneous submission rate in submissions/sec, e.g. {@code a - b}.
     */
    private double submissionRate(final ZonedDateTime a, final ZonedDateTime b) {
        return SUBMISSION_RATE_DIVIDEND / (a.toInstant().toEpochMilli() - b.toInstant().toEpochMilli());
    }


    /**
     * Schedule throttle job.
     */
    @PostConstruct
    public void scheduleThrottleJob() {
        try {
            if (shouldScheduleCleanerJob()) {
                logger.info("Preparing to schedule throttle job");

                final JobDetail job = JobBuilder.newJob(this.getClass())
                    .withIdentity(this.getClass().getSimpleName().concat(UUID.randomUUID().toString()))
                    .build();

                final Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(this.getClass().getSimpleName().concat(UUID.randomUUID().toString()))
                    .startAt(DateTimeUtils.dateOf(ZonedDateTime.now(ZoneOffset.UTC).plus(this.startDelay, ChronoUnit.MILLIS)))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(this.refreshInterval)
                        .repeatForever()).build();

                logger.debug("Scheduling {} job", this.getClass().getName());
                this.scheduler.scheduleJob(job, trigger);
                logger.info("{} will clean tickets every {} seconds",
                    this.getClass().getSimpleName(),
                    TimeUnit.MILLISECONDS.toSeconds(this.refreshInterval));
            }
        } catch (final Exception e){
            logger.warn(e.getMessage(), e);
        }

    }

    private boolean shouldScheduleCleanerJob() {
        if (this.startDelay > 0 && this.applicationContext.getParent() == null && this.scheduler != null) {
            logger.debug("Found CAS servlet application context");
            final String[] aliases =
                this.applicationContext.getAutowireCapableBeanFactory().getAliases("authenticationThrottle");
            logger.debug("{} is used as the active authentication throttle", this.getClass().getSimpleName());
            return aliases.length > 0 && aliases[0].equals(getName());
        }

        return false;
    }

    @Override
    public void execute(final JobExecutionContext jobExecutionContext) throws JobExecutionException {
        SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);
        try {
            logger.info("Beginning audit cleanup...");
            decrementCounts();
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

}
