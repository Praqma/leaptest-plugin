package com.customatics.leaptest_plugin;

import com.customatics.leaptest_plugin.model.Case;
import com.customatics.leaptest_plugin.model.InvalidSchedule;
import com.customatics.leaptest_plugin.model.Schedule;
import com.customatics.leaptest_plugin.model.ScheduleCollection;

import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

public class LeaptestJenkinsBridgeBuilder extends Builder  implements SimpleBuildStep {


    private String address;
    private String delay;
    private String doneStatusAs;
    private String report;
    private String schIds;
    private String schNames;

    private static PluginHandler pluginHandler = PluginHandler.getInstance();

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public LeaptestJenkinsBridgeBuilder( String address, String report, String schNames, String schIds )
    {

        this.address = address;
        this.delay = DescriptorImpl.DEFAULT_DELAY;
        this.doneStatusAs = "Success";
        this.report = report;
        this.schIds = schIds;
        this.schNames = schNames;
    }


    public String getDelay()        { return delay;}
    public String getAddress()      { return address;}
    public String getSchNames()     { return schNames;}
    public String getSchIds()       { return schIds;}
    public String getDoneStatusAs() { return doneStatusAs;}
    public String getReport()       { return  report;}

    @DataBoundSetter
    public void setDelay(String delay) { this.delay = delay; }

    @DataBoundSetter
    public void setDoneStatusAs(String doneStatusAs) {  this.doneStatusAs = doneStatusAs;}

    //@Override
    public void perform(final Run<?,?> build, FilePath workspace, Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {

        EnvVars env = build.getEnvironment(listener);
        HashMap<String, String> schedulesIdTitleHashMap = null; // Id-Title
        ArrayList<InvalidSchedule> invalidSchedules = new ArrayList<>();
        ScheduleCollection buildResult = new ScheduleCollection();
        ArrayList<String> rawScheduleList = null;

        String junitReportPath = pluginHandler.getJunitReportFilePath(env.get(Messages.JENKINS_WORKSPACE_VARIABLE), getReport());
        listener.getLogger().println(junitReportPath);
        env = null;

        String schId = null;
        String schTitle = null;

        rawScheduleList = pluginHandler.getRawScheduleList(getSchIds(),getSchNames());

        int timeDelay = Integer.parseInt(getDelay());

        try
        {    //Get schedule titles (or/and ids in case of pipeline)
            schedulesIdTitleHashMap = pluginHandler.getSchedulesIdTitleHashMap(getAddress(),rawScheduleList, listener, buildResult,invalidSchedules);
            rawScheduleList = null;                                        //don't need that anymore

            if(schedulesIdTitleHashMap.isEmpty())
            {
                throw new Exception(Messages.NO_SCHEDULES);
            }

            List<String> schIdsList = new ArrayList<>(schedulesIdTitleHashMap.keySet());

            int currentScheduleIndex = 0;
            boolean needSomeSleep = false;   //this time is required if there are schedules to rerun left
            while(!schIdsList.isEmpty())
            {

                if(needSomeSleep) {
                    Thread.sleep(timeDelay * 1000); //Time delay
                    needSomeSleep = false;
                }


                for(ListIterator<String> iter = schIdsList.listIterator(); iter.hasNext(); )
                {
                    schId = iter.next();
                    schTitle = schedulesIdTitleHashMap.get(schId);
                    RUN_RESULT runResult = pluginHandler.runSchedule(getAddress(), schId, schTitle, currentScheduleIndex, listener,  buildResult, invalidSchedules);
                    listener.getLogger().println("Current schedule index: " + currentScheduleIndex);

                    if (runResult.equals(RUN_RESULT.RUN_SUCCESS)) // if schedule was successfully run
                    {

                        boolean isStillRunning = true;

                        do
                        {

                            Thread.sleep(timeDelay * 1000); //Time delay
                            isStillRunning = pluginHandler.getScheduleState(getAddress(),schId,schTitle,currentScheduleIndex,listener, getDoneStatusAs(), buildResult, invalidSchedules);
                            if(isStillRunning) listener.getLogger().println(String.format(Messages.SCHEDULE_IS_STILL_RUNNING, schTitle, schId));

                        }
                        while (isStillRunning);

                        iter.remove();
                        currentScheduleIndex++;
                    }
                    else if (runResult.equals(RUN_RESULT.RUN_REPEAT))
                    {
                        needSomeSleep = true;
                    }
                    else
                    {
                        iter.remove();
                        currentScheduleIndex++;
                    }


                }
            }

            schIdsList = null;
            schedulesIdTitleHashMap = null;


            if (invalidSchedules.size() > 0)
            {
                listener.getLogger().println(Messages.INVALID_SCHEDULES);
                buildResult.Schedules.add(new Schedule(Messages.INVALID_SCHEDULES));

                for (InvalidSchedule invalidSchedule : invalidSchedules)
                {
                    listener.getLogger().println(invalidSchedule.getName());
                    buildResult.Schedules.get(buildResult.Schedules.size() - 1).Cases.add(new Case(invalidSchedule.getName(), "Failed", 0, invalidSchedule.getStackTrace(), "INVALID SCHEDULE"));
                }

            }

            for (Schedule schedule : buildResult.Schedules)
            {
                buildResult.addFailedTests(schedule.getFailed());
                buildResult.addPassedTests(schedule.getPassed());
                buildResult.addErrors(schedule.getErrors());
                schedule.setTotal(schedule.getPassed() + schedule.getFailed());
                buildResult.addTotalTime(schedule.getTime());
            }
            buildResult.setTotalTests(buildResult.getFailedTests() + buildResult.getPassedTests());

            pluginHandler.createJUnitReport(junitReportPath,listener,buildResult);

            if (buildResult.getErrors() > 0 || invalidSchedules.size() > 0) {
                listener.getLogger().println("There were detected 'ERRORS' or 'INVALID SCHEDULES' hence set the build status='FAILURE'");
                build.setResult(Result.FAILURE);
            } else if ( buildResult.getFailedTests() > 0 ) {
                if ( "Success".equals(this.doneStatusAs) ){
                    listener.getLogger().println("There were test cases that had failures/issues, but the plugin has been configured to return: 'Success' in this case");
                    build.setResult(Result.SUCCESS);
                } else if ( "Unstable".equals(this.doneStatusAs) ) {
                    listener.getLogger().println("There were test cases that had failures/issues, but the plugin has been configured to return: 'Unstable' in this case");
                    build.setResult(Result.UNSTABLE);
                }
            } else {
                listener.getLogger().println("No issues detected");
            }
            listener.getLogger().println(Messages.PLUGIN_SUCCESSFUL_FINISH);
        }

        catch (InterruptedException e)
        {
            String interruptedExceptionMessage = String.format(Messages.INTERRUPTED_EXCEPTION, e.getMessage());
            pluginHandler.stopSchedule(getAddress(),schId,schTitle, listener);
            throw new AbortException(interruptedExceptionMessage);
        }
        catch (Exception e)
        {
            listener.error(Messages.PLUGIN_ERROR_FINISH);
            listener.error(e.getMessage());
            listener.error(Messages.PLEASE_CONTACT_SUPPORT);
            listener.error("FAILURE");
            build.setResult(Result.FAILURE);
        }


        return;
    }



    @Override
    public  DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }


    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public static final String DEFAULT_DELAY = "3";
        public DescriptorImpl() { load();}

        public ListBoxModel doFillDoneStatusAsItems() {
            return new ListBoxModel(
                    new ListBoxModel.Option("Success", "Success" ),
                    new ListBoxModel.Option("Unstable", "Unstable" ),
                    new ListBoxModel.Option("Failed", "Failed" )
            );
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        public FormValidation doCheckDelay (@QueryParameter String delay){
            int temp;
            try {
                temp = Integer.parseInt(delay);
                if ( temp < 1 ){
                    return FormValidation.error("Entered number must be higher than 0");
                }

            } catch (NumberFormatException ex){
                return FormValidation.error("Invalid number");
            }
            return FormValidation.ok();

        }

        public String getDefaultDelay() { return DEFAULT_DELAY; }

        public String getDisplayName() {
            return Messages.PLUGIN_NAME;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req,formData);
        }

    }



}

