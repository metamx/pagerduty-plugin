package org.jenkinsci.plugins.pagerduty;

/**
 * Created by alexander on 9/15/15.
 */

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.squareup.pagerduty.incidents.NotifyResult;
import com.squareup.pagerduty.incidents.PagerDuty;
import com.squareup.pagerduty.incidents.Resolution;
import com.squareup.pagerduty.incidents.Trigger;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class PagerDutyTrigger extends Notifier{


    private enum ValidationResult {
        DO_NOTHING, DO_TRIGGER, DO_RESOLVE
    }

    private static final String DEFAULT_DESCRIPTION_STRING = "I was too lazy to create a description, but trust me it's important!";

    public  String serviceKey;

    public  boolean resolveOnBackToNormal;
    public  boolean triggerOnSuccess;
    public  boolean triggerOnFailure;
    public  boolean triggerOnUnstable;
    public  boolean triggerOnAborted;
    public  boolean triggerOnNotBuilt;
    public  String incidentKey;
    public  String incDescription;
    public  String incDetails;
    public  Integer numPreviousBuildsToProbe;

    public boolean isResolveOnBackToNormal() { return resolveOnBackToNormal; }

    public void setResolveOnBackToNormal(boolean resolveOnBackToNormal) { this.resolveOnBackToNormal = resolveOnBackToNormal; }

    public String getServiceKey() {
        return serviceKey;
    }

    public boolean isTriggerOnSuccess() {
        return triggerOnSuccess;
    }

    public boolean isTriggerOnFailure() {
        return triggerOnFailure;
    }

    public boolean isTriggerOnUnstable() {
        return triggerOnUnstable;
    }

    public boolean isTriggerOnAborted() {
        return triggerOnAborted;
    }

    public boolean isTriggerOnNotBuilt() {
        return triggerOnNotBuilt;
    }

    public String getIncidentKey() {
        return incidentKey;
    }

    public String getIncDescription() {
        return incDescription;
    }

    public String getIncDetails() {
        return incDetails;
    }

    public Integer getNumPreviousBuildsToProbe() {
        return numPreviousBuildsToProbe;
    }

    protected Object readResolve() {
//        this.getDescriptor().load();
        return this;
    }

    @Override
    public DescriptorImpl getDescriptor(){
        Jenkins j = Jenkins.getInstance();
        return (j != null)?j.getDescriptorByType(DescriptorImpl.class):null;
    }

    @DataBoundConstructor
    public PagerDutyTrigger(String serviceKey,boolean resolveOnBackToNormal, boolean triggerOnSuccess, boolean triggerOnFailure, boolean triggerOnAborted,
                            boolean triggerOnUnstable, boolean triggerOnNotBuilt, String incidentKey, String incDescription, String incDetails,
                            Integer numPreviousBuildsToProbe) {
        super();
        this.serviceKey = serviceKey;
        this.resolveOnBackToNormal = resolveOnBackToNormal;
        this.triggerOnSuccess = triggerOnSuccess;
        this.triggerOnFailure = triggerOnFailure;
        this.triggerOnUnstable = triggerOnUnstable;
        this.triggerOnAborted = triggerOnAborted;
        this.triggerOnNotBuilt = triggerOnNotBuilt;
        this.incidentKey = incidentKey;
        this.incDescription = incDescription;
        this.incDetails = incDetails;
        this.numPreviousBuildsToProbe = (numPreviousBuildsToProbe != null && numPreviousBuildsToProbe > 0) ? numPreviousBuildsToProbe : 1;
    }

    private LinkedList<Result> generateResultProbe() {
        LinkedList<Result> res = new LinkedList<Result>();
        if(triggerOnSuccess)
            res.add(Result.SUCCESS);
        if(triggerOnFailure)
            res.add(Result.FAILURE);
        if(triggerOnUnstable)
            res.add(Result.UNSTABLE);
        if(triggerOnAborted)
            res.add(Result.ABORTED);
        if(triggerOnNotBuilt)
            res.add(Result.NOT_BUILT);
        return res;
    }

    /*
     * method to fetch and replace possible Environment Variables from job parameteres
     */
    private String replaceEnvVars(String str, EnvVars envv, String defaultString) {
        StringBuffer sb = new StringBuffer();
        if (str == null || str.trim().length() < 1) {
            if (defaultString == null)
                return null;
            str = defaultString;
        }
        Matcher m = Pattern.compile("\\$\\{.*\\}|\\$[^\\-\\*\\.#!, ]*")
                .matcher(str);
        while (m.find()) {
            String v = m.group();
            v = v.replaceAll("\\$", "").replaceAll("\\{", "").replaceAll("\\}", "");
            m.appendReplacement(sb, envv.get(v, ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /*
     * method to verify X previous builds finished with the desired result
     */
    private ValidationResult validWithPreviousResults(AbstractBuild<?, ?> build, List<Result> desiredResultList, int depth) {
        int i = 0;
        if (this.resolveOnBackToNormal && build != null && Result.SUCCESS.equals(build.getResult())) {
            AbstractBuild<?, ?> previousBuild = build.getPreviousBuild();
            if (previousBuild != null && !Result.SUCCESS.equals(previousBuild.getResult()))
                return ValidationResult.DO_RESOLVE;
        }else {
            while (i < depth && build != null) {
                if (!desiredResultList.contains(build.getResult())) {
                    break;
                }
                i++;
                build = build.getPreviousBuild();
            }
            if (i == depth) {
                return ValidationResult.DO_TRIGGER;
            }
        }
        return ValidationResult.DO_NOTHING;
    }

    /*
     * (non-Javadoc)
     *
     * @see hudson.tasks.BuildStep#getRequiredMonitorService()
     */
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * hudson.tasks.BuildStepCompatibilityLayer#perform(hudson.model.AbstractBuild
     * , hudson.Launcher, hudson.model.BuildListener)
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                           BuildListener listener) throws InterruptedException, IOException {
        PagerDuty pagerDuty = null;
        LinkedList<Result> resultProbe = generateResultProbe();

        EnvVars env = build.getEnvironment(listener);
        ValidationResult validationResult = validWithPreviousResults(build, resultProbe, numPreviousBuildsToProbe);
        if (validationResult != ValidationResult.DO_NOTHING ) {

            if(this.serviceKey != null && this.serviceKey.trim().length() > 0)
                pagerDuty = PagerDuty.create(serviceKey);

            if (validationResult == ValidationResult.DO_TRIGGER) {
                listener.getLogger().println("Triggering PagerDuty Notification");
                return triggerPagerDuty(listener, env, pagerDuty);
            }else if (validationResult == ValidationResult.DO_RESOLVE){
                listener.getLogger().println("Resolving incident");
                return resolveIncident(pagerDuty, listener.getLogger());
            }
        }
        return true;
    }

    private boolean resolveIncident(PagerDuty pagerDuty, PrintStream logger) {
        Resolution resolution = new Resolution.Builder(this.incidentKey)
                .withDescription("Automatically Back to normal")
                .build();
        try{
            NotifyResult result = pagerDuty.notify(resolution);
            if (result != null) {
                logger.println("Finished resolving - " + result.status());
            } else {
                logger.println("Attempt to resolve the incident returned null - Incident may already be closed or may not exist.");
            }
        } catch (IOException e){
            logger.println("Error while trying to resolve ");
            logger.println(e.getMessage());
            return false;
        }
        return true;
    }

    private boolean triggerPagerDuty(BuildListener listener, EnvVars env, PagerDuty pagerDuty) {

        if (pagerDuty == null) {
            listener.getLogger().println("Unable to activate pagerduty module, check configuration!");
            return false;
        }

        String descr = replaceEnvVars(this.incDescription, env, DEFAULT_DESCRIPTION_STRING);
        String serviceK = replaceEnvVars(this.serviceKey, env, null);
        String incK = replaceEnvVars(this.incidentKey, env, null);
        String details = replaceEnvVars(this.incDetails, env, null);
        boolean hasIncidentKey = false;

        if (incK != null && incK.length() > 0) {
            hasIncidentKey = true;
        }

        listener.getLogger().printf("Triggering pagerDuty with serviceKey: %s%n", serviceK);

        try {
            Trigger trigger;
            listener.getLogger().printf("Triggering pagerDuty with incidentKey: %s%n", incK);
            listener.getLogger().printf("Triggering pagerDuty with incDescription: %s%n", descr);
            listener.getLogger().printf("Triggering pagerDuty with incDetails: %s%n", details);

            Trigger.Builder triggerBuilder = new Trigger.Builder(descr);
            try {
                listener.getLogger().printf("Trying to read the json format: %s%n", details);
                GsonBuilder builder = new GsonBuilder();
                Gson gson = builder.create();
                Map<String, String> detailsMap = gson.fromJson(details, Map.class);
                triggerBuilder.addDetails(detailsMap);
            } catch (Exception e){
                listener.getLogger().printf("Error: %s%n", e.getMessage());
                if (details != null) {
                    triggerBuilder.addDetails("Details", details);
                }
            }

            if (hasIncidentKey) {
                trigger = triggerBuilder.withIncidentKey(incK).build();
            } else {
                trigger = triggerBuilder.build();
            }

            NotifyResult result = pagerDuty.notify(trigger);
            if (result != null) {
                if (!hasIncidentKey) {
                    this.incidentKey = result.incidentKey();
                }
                listener.getLogger().printf("PagerDuty Notification Result: %s%n", result.status());
                listener.getLogger().printf("PagerDuty IncidentKey: %s%n", this.incidentKey);
            } else {
                listener.getLogger().printf("PagerDuty returned NULL. check network or PD settings!");
            }
        } catch (Exception e) {
            e.printStackTrace(listener.error("Tried to trigger PD with serviceKey = [%s]",
                    serviceK));
            return false;
        }
        return true;
    }

    @Extension
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Publisher> {

        /*
         * (non-Javadoc)
         *
         * @see hudson.tasks.BuildStepDescriptor#isApplicable(java.lang.Class)
         */
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        /*
         * (non-Javadoc)
         *
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName() {
            return "PagerDuty Incident Trigger";
        }

    }
}
