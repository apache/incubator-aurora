package com.twitter.mesos.scheduler.httphandlers;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.antlr.stringtemplate.StringTemplate;

import com.twitter.common.base.Closure;
import com.twitter.common.net.http.handlers.StringTemplateServlet;
import com.twitter.mesos.gen.JobConfiguration;
import com.twitter.mesos.gen.ScheduledTask;
import com.twitter.mesos.scheduler.ClusterName;
import com.twitter.mesos.scheduler.CronJobManager;
import com.twitter.mesos.scheduler.Query;
import com.twitter.mesos.scheduler.SchedulerCore;
import com.twitter.mesos.scheduler.quota.QuotaManager;

import static com.google.common.base.Preconditions.checkNotNull;

import static com.twitter.common.base.MorePreconditions.checkNotBlank;

/**
 * HTTP interface to provide information about jobs for a specific mesos role.
 */
public class SchedulerzRole extends StringTemplateServlet {

  private static final String ROLE_PARAM = "role";

  private final SchedulerCore scheduler;
  private final CronJobManager cronScheduler;
  private final String clusterName;
  private final QuotaManager quotaManager;

  /**
   * Creates a new role servlet.
   *
   * @param cacheTemplates Whether to cache the template file.
   * @param scheduler Core scheduler.
   * @param cronScheduler Cron scheduler.
   * @param clusterName Name of the serving cluster.
   * @param quotaManager Resource quota manager.
   */
  @Inject
  public SchedulerzRole(@CacheTemplates boolean cacheTemplates,
      SchedulerCore scheduler,
      CronJobManager cronScheduler,
      @ClusterName String clusterName,
      QuotaManager quotaManager) {
    super("schedulerzrole", cacheTemplates);
    this.scheduler = checkNotNull(scheduler);
    this.cronScheduler = checkNotNull(cronScheduler);
    this.clusterName = checkNotBlank(clusterName);
    this.quotaManager = checkNotNull(quotaManager);
  }

  @Override
  protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {

    final String role = req.getParameter(ROLE_PARAM);

    writeTemplate(resp, new Closure<StringTemplate>() {
      @Override public void execute(StringTemplate template) {
        template.setAttribute("cluster_name", clusterName);

        if (role == null) {
          template.setAttribute("exception", "Please specify a user.");
          return;
        }
        template.setAttribute("role", role);

        Map<String, Job> jobs = Maps.newHashMap();
        for (ScheduledTask task : scheduler.getTasks(Query.byRole(role))) {
          Job job = jobs.get(task.getAssignedTask().getTask().getJobName());

          if (job == null) {
            job = new Job();
            job.name = task.getAssignedTask().getTask().getJobName();
            jobs.put(job.name, job);
          }

          switch (task.getStatus()) {
            case INIT:
            case PENDING:
              job.pendingTaskCount++;
              break;

            case ASSIGNED:
            case STARTING:
            case RESTARTING:
            case UPDATING:
            case RUNNING:
              job.activeTaskCount++;
              break;

            case KILLING:
            case KILLED:
            case FINISHED:
            case PREEMPTING:
            case ROLLBACK:
              job.finishedTaskCount++;
              break;

            case LOST:
            case FAILED:
            case UNKNOWN:
              job.failedTaskCount++;
              break;

            default:
              throw new IllegalArgumentException("Unsupported status: " + task.getStatus());
          }
        }

        template.setAttribute("jobs", DisplayUtils.JOB_ORDERING.sortedCopy(jobs.values()));

        Iterable<JobConfiguration> cronJobs = Iterables.filter(
            cronScheduler.getJobs(), new Predicate<JobConfiguration>() {
              @Override public boolean apply(JobConfiguration job) {
                return job.getOwner().getRole().equals(role);
              }
            });

        cronJobs = DisplayUtils.JOB_CONFIG_ORDERING.sortedCopy(cronJobs);
        Iterable<CronJob> cronJobObjs = Iterables.transform(cronJobs,
            new Function<JobConfiguration, CronJob>() {
              @Override public CronJob apply(JobConfiguration job) {
                CronJob cronJob = new CronJob();
                cronJob.name = job.getName();
                cronJob.pendingTaskCount = job.getTaskConfigsSize();
                cronJob.cronSchedule = job.getCronSchedule();
                cronJob.nextRun = CronJobManager.predictNextRun(cronJob.cronSchedule).getTime();
                return cronJob;
              }
            });

        template.setAttribute("cronJobs", Lists.newArrayList(cronJobObjs));
        template.setAttribute("resourcesUsed", quotaManager.getConsumption(role));
        template.setAttribute("resourceQuota", quotaManager.getQuota(role));
      }
    });
  }

  /**
   * Template object to represent a job.
   */
  static class Job {
    String name;
    int pendingTaskCount = 0;
    int activeTaskCount = 0;
    int finishedTaskCount = 0;
    int failedTaskCount = 0;

    public String getName() {
      return name;
    }

    public int getPendingTaskCount() {
      return pendingTaskCount;
    }

    public int getActiveTaskCount() {
      return activeTaskCount;
    }

    public int getFinishedTaskCount() {
      return finishedTaskCount;
    }

    public int getFailedTaskCount() {
      return failedTaskCount;
    }
  }

  class CronJob extends Job {
    String cronSchedule;
    long nextRun;

    public String getCronSchedule() {
      return cronSchedule;
    }

    public long getNextRun() {
      return nextRun;
    }
  }
}
