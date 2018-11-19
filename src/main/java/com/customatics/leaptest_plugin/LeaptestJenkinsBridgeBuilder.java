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
import jenkins.model.Jenkins;
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

        listener.getLogger().println("");
        listener.getLogger().println("LeapWork Plugin configuration:");
        listener.getLogger().println("---------------------------------------------------------");

        listener.getLogger().println("LeapWork controller:         " + getLeapworkHostname());
        listener.getLogger().println("LeapWork port:               " + getLeapworkPort());
        listener.getLogger().println("Report filename:             " + getLeapworkReport());
        listener.getLogger().println("Schedule names:              " + getLeapworkSchNames());
        listener.getLogger().println("Schedule ID's:               " + getLeapworkSchIds());
        listener.getLogger().println("Delay between status checks: " + getLeapworkDelay());
        listener.getLogger().println("DoneStatusAs:                " + getLeapworkDoneStatusAs());
        listener.getLogger().println("");


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
                listener.getLogger().println(String.format("ERROR: No or invalid schedule id's detected.."));
                listener.getLogger().println(String.format(""));
                throw new Exception(Messages.NO_SCHEDULES);
            }
            if (invalidSchedules.size() > 0)
            {
                listener.getLogger().println(String.format("ERROR: No or invalid schedule titles detected.."));
                for (InvalidSchedule invalidSchedule : invalidSchedules) {
                    listener.getLogger().println(String.format("%1$s: %2$s",invalidSchedule.getName(),invalidSchedule.getStackTrace()));
                }
                listener.getLogger().println(String.format(""));
                throw new Exception(Messages.INVALID_SCHEDULES);
            }

            List<UUID> schIdsList = new ArrayList<>(schedulesIdTitleHashMap.keySet());
            HashMap<UUID, LeapworkRun> resultsMap = new HashMap<>();

            ListIterator<UUID> iter = schIdsList.listIterator();
            while( iter.hasNext())
            {

                UUID schId = iter.next();
                String schTitle = schedulesIdTitleHashMap.get(schId);

                // FIXME: ask status for schedule before running it
                // LeapWork of the new version does not have a queue for each schedule - only for environments
                boolean scheduleCanRun = false;
                while ( !scheduleCanRun ){
                    String scheduleStatus = pluginHandler.getScheduleIdStatus(mainClient,controllerApiHttpAddress,leapworkAccessKey,schId);
                    if ( "Finished".equals(scheduleStatus) ){
                        listener.getLogger().println(String.format("The schedule is ready to run as the state is: '%1$s' - let's go", scheduleStatus));
                        scheduleCanRun = true;
                    } else {
                        listener.getLogger().println(String.format("The schedule status is already '%1$s' - wait a minute...", scheduleStatus));
                        Thread.sleep(60000);
                    }
                }

                LeapworkRun leapWorkRun  = new LeapworkRun(schTitle);

                UUID runId = pluginHandler.runSchedule(mainClient,controllerApiHttpAddress, leapworkAccessKey, schId, schTitle, listener,  leapWorkRun);
                if(runId != null)
                {
                    resultsMap.put(runId,leapWorkRun);
                    boolean runIdReady = false;
                    while ( !runIdReady ){
                        if ( pluginHandler.scheduleRunIdFound(mainClient,controllerApiHttpAddress,leapworkAccessKey,schId,runId)) {
                            listener.getLogger().println(String.format("RunId: '%1$s' is ready to monitor", runId ));
                            runIdReady = true;
                        } else {
                            listener.getLogger().println(String.format("RunId: '%1$s' is not ready yet - wait a minute", runId ));
                            Thread.sleep(60000);
                        }
                    }
                    CollectScheduleRunResults(controllerApiHttpAddress, leapworkAccessKey,runId,schTitle,timeDelay,leapworkDoneStatusAs,leapWorkRun, listener);
                }
                else
                    resultsMap.put(UUID.randomUUID(),leapWorkRun);

                iter.remove();

            }

            schIdsList.clear();
            schedulesIdTitleHashMap.clear();
            RunCollection buildResult = new RunCollection();

            listener.getLogger().println("##################################");
            listener.getLogger().println("LeapWork summary: ");
            listener.getLogger().println("##################################");
            List<LeapworkRun> resultRuns = new ArrayList<>(resultsMap.values());
            for (LeapworkRun leapworkRun : resultRuns)
            {
                buildResult.leapworkRuns.add(leapworkRun);

                buildResult.addFailedTests(leapworkRun.getFailed());
                buildResult.addPassedTests(leapworkRun.getPassed());
                buildResult.addErrors(leapworkRun.getErrors());
                leapworkRun.setTotal(leapworkRun.getPassed() + leapworkRun.getFailed());
                buildResult.addTotalTime(leapworkRun.getTime());
                listener.getLogger().println("" + leapworkRun.getScheduleTitle());
                listener.getLogger().println("Passed testcases: " + leapworkRun.getPassed() );
                listener.getLogger().println("Failed testcases: " + leapworkRun.getFailed() );
                listener.getLogger().println("Error testcases: " + leapworkRun.getErrors() );
                listener.getLogger().println("");

            }
            buildResult.setTotalTests(buildResult.getFailedTests() + buildResult.getPassedTests());

            listener.getLogger().println("|---------------------------------------------------------------");
            listener.getLogger().println("| Total passed testcases: " + buildResult.getPassedTests() );
            listener.getLogger().println("| Total failed testcases: " + buildResult.getFailedTests() );
            listener.getLogger().println("| Total error testcases: " + buildResult.getErrors() );
            listener.getLogger().println("|---------------------------------------------------------------");
            listener.getLogger().println("");


            pluginHandler.createJUnitReport(run,workspace,getLeapworkReport(),listener,buildResult);

            if (buildResult.getErrors() > 0 ) {
                listener.getLogger().println("[ERROR] There were detected case(s) with status 'Error', 'Inconclusive', 'Timeout' or 'Cancelled'. Please check the report or console output for details. Set the build status to FAILURE as the results of the cases are not deterministic..");
                run.setResult(Result.FAILURE);
                listener.getLogger().println("");
            }
            if ( buildResult.getFailedTests() > 0 ) {
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

