package com.customatics.leaptest_plugin;

import com.customatics.leaptest_plugin.model.LeapworkRun;
import com.customatics.leaptest_plugin.model.RunItem;
import com.customatics.leaptest_plugin.model.InvalidSchedule;
import com.customatics.leaptest_plugin.model.RunCollection;
import com.ning.http.client.AsyncHttpClient;
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
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class LeaptestJenkinsBridgeBuilder extends Builder  implements SimpleBuildStep {

    private String leapworkHostname;
    private String leapworkPort;
    private String leapworkAccessKey;
    private String leapworkDelay;
    private String leapworkDoneStatusAs;
    private String leapworkReport;
    private String leapworkSchIds;
    private String leapworkSchNames;

    private static PluginHandler pluginHandler = PluginHandler.getInstance();

    /**
     * @param leapworkHostname
     * @param leapworkPort
     * @param leapworkAccessKey
     * @param leapworkSchNames
     * @param leapworkSchIds
     */
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public LeaptestJenkinsBridgeBuilder(String leapworkHostname,String leapworkPort, String leapworkAccessKey, String leapworkSchNames, String leapworkSchIds )
    {

        this.leapworkHostname = leapworkHostname;
        this.leapworkPort = leapworkPort;
        this.leapworkAccessKey = leapworkAccessKey;
        this.leapworkDelay = DescriptorImpl.DEFAULT_DELAY;
        this.leapworkDoneStatusAs = DescriptorImpl.DEFAULT_DONE_STATUS;
        this.leapworkReport = DescriptorImpl.DEFAULT_REPORTNAME;
        this.leapworkSchIds = leapworkSchIds;
        this.leapworkSchNames = leapworkSchNames;
    }

    public String getLeapworkHostname()     { return leapworkHostname;}
    public String getLeapworkPort()         { return leapworkPort;}
    public String getLeapworkAccessKey()    { return leapworkAccessKey;}
    public String getLeapworkDelay()        { return leapworkDelay;}
    public String getLeapworkSchNames()     { return leapworkSchNames;}
    public String getLeapworkSchIds()       { return leapworkSchIds;}
    public String getLeapworkDoneStatusAs() { return leapworkDoneStatusAs;}
    public String getLeapworkReport()       { return leapworkReport;}

    @DataBoundSetter
    public void setLeapworkReport(String leapworkReport) { this.leapworkReport = leapworkReport; }

    @DataBoundSetter
    public void setLeapworkDelay(String leapworkDelay) { this.leapworkDelay = leapworkDelay; }

    @DataBoundSetter
    public void setLeapworkDoneStatusAs(String leapworkDoneStatusAs) {  this.leapworkDoneStatusAs = leapworkDoneStatusAs;}

    /**
     * @param run
     * @param workspace
     * @param launcher
     * @param listener
     * @throws IOException
     * @throws InterruptedException
     */
    //@Override
    public void perform(final Run<?,?> run, FilePath workspace, Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {

        HashMap<UUID, String> schedulesIdTitleHashMap = null; // Id-Title
        ArrayList<InvalidSchedule> invalidSchedules = new ArrayList<>();
        ArrayList<String> rawScheduleList = null;

        listener.getLogger().println(getLeapworkReport());


        rawScheduleList = pluginHandler.getRawScheduleList(leapworkSchIds, leapworkSchNames);
        String controllerApiHttpAddress = pluginHandler.getControllerApiHttpAdderess(leapworkHostname, leapworkPort, listener);

        int timeDelay = Integer.parseInt(getLeapworkDelay());

        try( AsyncHttpClient mainClient = new AsyncHttpClient())
        {

            //Get schedule titles (or/and ids in case of pipeline)
            schedulesIdTitleHashMap = pluginHandler.getSchedulesIdTitleHashMap(mainClient, leapworkAccessKey, controllerApiHttpAddress,rawScheduleList, listener,invalidSchedules);
            rawScheduleList.clear();//don't need that anymore

            if(schedulesIdTitleHashMap.isEmpty())
            {
                throw new Exception(Messages.NO_SCHEDULES);
            }

            List<UUID> schIdsList = new ArrayList<>(schedulesIdTitleHashMap.keySet());
            HashMap<UUID, LeapworkRun> resultsMap = new HashMap<>();

            ListIterator<UUID> iter = schIdsList.listIterator();
            while( iter.hasNext())
            {

                UUID schId = iter.next();
                String schTitle = schedulesIdTitleHashMap.get(schId);
                LeapworkRun leapWorkRun  = new LeapworkRun(schTitle);

                UUID runId = pluginHandler.runSchedule(mainClient,controllerApiHttpAddress, leapworkAccessKey, schId, schTitle, listener,  leapWorkRun);
                if(runId != null)
                {
                    resultsMap.put(runId,leapWorkRun);
                    CollectScheduleRunResults(controllerApiHttpAddress, leapworkAccessKey,runId,schTitle,timeDelay,leapworkDoneStatusAs,leapWorkRun, listener);
                }
                else
                    resultsMap.put(UUID.randomUUID(),leapWorkRun);

                iter.remove();

            }

            schIdsList.clear();
            schedulesIdTitleHashMap.clear();
            RunCollection buildResult = new RunCollection();

            if (invalidSchedules.size() > 0)
            {
                listener.getLogger().println(Messages.INVALID_SCHEDULES);

                for (InvalidSchedule invalidSchedule : invalidSchedules)
                {
                    listener.getLogger().println(String.format("%1$s: %2$s",invalidSchedule.getName(),invalidSchedule.getStackTrace()));
                    LeapworkRun notFoundSchedule = new LeapworkRun(invalidSchedule.getName());
                    RunItem invalidRunItem = new RunItem("Error","Error",0,invalidSchedule.getStackTrace(),invalidSchedule.getName());
                    notFoundSchedule.runItems.add(invalidRunItem);
                    notFoundSchedule.setError(invalidSchedule.getStackTrace());
                    buildResult.leapworkRuns.add(notFoundSchedule);
                }

            }

            List<LeapworkRun> resultRuns = new ArrayList<>(resultsMap.values());
            for (LeapworkRun leapworkRun : resultRuns)
            {
                buildResult.leapworkRuns.add(leapworkRun);

                buildResult.addFailedTests(leapworkRun.getFailed());
                buildResult.addPassedTests(leapworkRun.getPassed());
                buildResult.addErrors(leapworkRun.getErrors());
                leapworkRun.setTotal(leapworkRun.getPassed() + leapworkRun.getFailed());
                buildResult.addTotalTime(leapworkRun.getTime());
            }
            buildResult.setTotalTests(buildResult.getFailedTests() + buildResult.getPassedTests());

            pluginHandler.createJUnitReport(run,workspace,getLeapworkReport(),listener,buildResult);

            if (buildResult.getErrors() > 0 || invalidSchedules.size() > 0) {
                listener.getLogger().println("There were detected 'ERRORS' or 'INVALID SCHEDULES' hence set the build status='FAILURE'");
                run.setResult(Result.FAILURE);
            } else if ( buildResult.getFailedTests() > 0 ) {
                if ( "Success".equals(this.leapworkDoneStatusAs) ){
                    listener.getLogger().println("There were test cases that had failures/issues, but the plugin has been configured to return: 'Success' in this case");
                    run.setResult(Result.SUCCESS);
                } else if ( "Unstable".equals(this.leapworkDoneStatusAs) ) {
                    listener.getLogger().println("There were test cases that had failures/issues, but the plugin has been configured to return: 'Unstable' in this case");
                    run.setResult(Result.UNSTABLE);
                }
            } else {
                listener.getLogger().println("No issues detected");
            }
            listener.getLogger().println(Messages.PLUGIN_SUCCESSFUL_FINISH);

        }
        catch (AbortException | InterruptedException e)
        {
            listener.error("ABORTED");
            run.setResult(Result.ABORTED);
            listener.error(Messages.PLUGIN_ERROR_FINISH);
        }
        catch (Exception e)
        {
            listener.error(Messages.PLUGIN_ERROR_FINISH);
            listener.error(e.getMessage());
            listener.error(Messages.PLEASE_CONTACT_SUPPORT);
            listener.error("FAILURE");
            run.setResult(Result.FAILURE);
        }


        return;
    }


    private static void CollectScheduleRunResults(String controllerApiHttpAddress, String accessKey, UUID runId, String scheduleName, int timeDelay,String doneStatusAs, LeapworkRun resultRun,  final TaskListener listener) throws AbortException, InterruptedException {
        List<UUID> runItemsId = new ArrayList<>();
        Object waiter = new Object();
        //get statuses
        try(AsyncHttpClient client = new AsyncHttpClient())
        {
            boolean isStillRunning = true;

            do
            {
                synchronized (waiter)
                {
                    waiter.wait(timeDelay * 1000);//Time delay
                }

                List<UUID> executedRunItems = pluginHandler.getRunRunItems(client,controllerApiHttpAddress,accessKey,runId);
                executedRunItems.removeAll(runItemsId); //left only new


                for(ListIterator<UUID> iter = executedRunItems.listIterator(); iter.hasNext();)
                {
                    UUID runItemId = iter.next();
                    RunItem runItem = pluginHandler.getRunItem(client,controllerApiHttpAddress,accessKey,runItemId, scheduleName,listener );

                    String status = runItem.getCaseStatus();


                    resultRun.addTime(runItem.getElapsedTime());
                    switch (status)
                    {
                        case "NoStatus":
                        case "Initializing":
                        case "Connecting":
                        case "Connected":
                        case "Running":
                            iter.remove();
                            break;
                        case "Passed":
                            resultRun.incPassed();
                            resultRun.runItems.add(runItem);
                            resultRun.incTotal();
                            break;
                        case "Failed":
                            resultRun.incFailed();
                            resultRun.runItems.add(runItem);
                            resultRun.incTotal();
                            break;
                        case "Error":
                        case "Inconclusive":
                        case "Timeout":
                        case "Cancelled":
                            resultRun.incErrors();
                            resultRun.runItems.add(runItem);
                            resultRun.incTotal();
                            break;
                        case"Done":
                            resultRun.runItems.add(runItem);
                            if(doneStatusAs.contentEquals("Success"))
                                resultRun.incPassed();
                            else
                                resultRun.incFailed();
                            resultRun.incTotal();
                            break;

                    }

                }

                runItemsId.addAll(executedRunItems);

                String runStatus = pluginHandler.getRunStatus(client,controllerApiHttpAddress,accessKey,runId);
                if(runStatus.contentEquals("Finished"))
                {
                    List<UUID> allExecutedRunItems = pluginHandler.getRunRunItems(client,controllerApiHttpAddress,accessKey,runId);
                    if(allExecutedRunItems.size() > 0 && allExecutedRunItems.size() <= runItemsId.size())
                        isStillRunning = false;
                }

            }
            while (isStillRunning);

        }
        catch (AbortException | InterruptedException e)
        {
            Lock lock = new ReentrantLock();
            lock.lock();
            try
            {
                String interruptedExceptionMessage = String.format(Messages.INTERRUPTED_EXCEPTION, e.getMessage());
                listener.error(interruptedExceptionMessage);
                RunItem invalidItem = new RunItem("Aborted run","Cancelled",0,e.getMessage(),scheduleName);
                pluginHandler.stopRun(controllerApiHttpAddress,runId,scheduleName,accessKey, listener);
                resultRun.incErrors();
                resultRun.runItems.add(invalidItem);
            }
            finally {
                lock.unlock();
                throw e;
            }
        }
        catch (Exception e)
        {
            listener.error(e.getMessage());
            RunItem invalidItem = new RunItem("Invalid run","Error",0,e.getMessage(),scheduleName);
            resultRun.incErrors();
            resultRun.runItems.add(invalidItem);
        }
    }

    @Override
    public  DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }


    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public static final String DEFAULT_DELAY = "3";
        public static final String DEFAULT_REPORTNAME = "report.xml";
        public static final String DEFAULT_DONE_STATUS = "Success";

        public DescriptorImpl() { load();}

        public ListBoxModel doFillLeapworkDoneStatusAsItems() {
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

        public FormValidation doCheckLeapworkDelay (@QueryParameter String leapworkDelay){
            int temp;
            try {
                temp = Integer.parseInt(leapworkDelay);
                if ( temp < 1 ){
                    return FormValidation.error("Entered number must be higher than 0");
                }

            } catch (NumberFormatException ex){
                return FormValidation.error("Invalid number");
            }
            return FormValidation.ok();

        }

        public String getDefaultLeapworkDelay() {
            return DEFAULT_DELAY;
        }

        public String getDefaultLeapworkReportname() {
            return DEFAULT_REPORTNAME;
        }

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

