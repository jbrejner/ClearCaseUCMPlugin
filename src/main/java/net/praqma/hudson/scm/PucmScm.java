package net.praqma.hudson.scm;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.scm.SCM;
import hudson.util.FormValidation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import net.praqma.clearcase.ucm.entities.Baseline;
import net.praqma.clearcase.ucm.entities.SnapshotView;
import net.praqma.clearcase.ucm.entities.Tag;
import net.praqma.clearcase.ucm.entities.Activity;
import net.praqma.clearcase.ucm.entities.Baseline.BaselineDiff;
import net.praqma.clearcase.ucm.entities.Component;
import net.praqma.clearcase.ucm.entities.Component.BaselineList;
import net.praqma.clearcase.ucm.entities.Stream;
import net.praqma.clearcase.ucm.entities.UCMEntity;
import net.praqma.clearcase.ucm.entities.UCMEntityException;
import net.praqma.clearcase.ucm.entities.Version;
import net.praqma.clearcase.ucm.utils.TagQuery;
import net.praqma.debug.Debug;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * CC4HClass is responsible for everything regarding Hudsons connection to
 * ClearCase pre-build. This class defines all the files required by the user.
 * The information can be entered on the config page.
 * 
 * @author Troels Selch S�rensen
 * @author Margit Bennetzen
 * 
 */
public class PucmScm extends SCM {

	private String levelToPoll;
	private String loadModule;
	private String component;
	private String stream;
	private boolean newest;
	private boolean newerThanRecommended;

	private Baseline bl;
	private List<String> levels = null;
	private List<String> loadModules = null;
	private BaselineList baselines;
	private boolean compRevCalled;
	private String pollMsgs = "";
	private Stream st;

	protected static Debug logger = Debug.GetLogger();

	/**
	 * The constructor is used by Hudson to create the instance of the plugin
	 * needed for a connection to ClearCase. It is annotated with
	 * <code>@DataBoundConstructor</code> to tell Hudson where to put the
	 * information retrieved from the configuration page in the WebUI.
	 * 
	 * @param component
	 *            This string defines the component needed to find baselines.
	 * @param levelToPoll
	 *            This string defines the level to poll ClearCase for.
	 * @param loadModule
	 *            This string tells if we should load all modules or only the
	 *            ones that are modifiable.
	 * @param stream
	 *            This string defines the stream needed to find baselines.
	 * @param newest
	 *            This boolean tells whether we should build only the newest
	 *            baseline.
	 * @param newerThanRecommended
	 *            This boolean tells whether we should look at all baselines or
	 *            only ones newer than the recommended baseline
	 */
	@DataBoundConstructor
	public PucmScm(String component, String levelToPoll, String loadModule,
			String stream, boolean newest, boolean newerThanRecommended) {
		logger.trace_function();
		this.component = component;
		this.levelToPoll = levelToPoll;
		this.loadModule = loadModule;
		this.stream = stream;
		this.newest = newest;
		this.newerThanRecommended = newerThanRecommended;
	}

	/**
	 * The repository is checked for new baselines, and if any, then the oldest
	 * will be built.
	 * 
	 */
	@Override
	public boolean checkout(AbstractBuild build, Launcher launcher,
			FilePath workspace, BuildListener listener, File changelogFile)
			throws IOException, InterruptedException {
		logger.trace_function();
		PrintStream hudsonOut = listener.getLogger();

		/*
		 * Examples to use from testbase.xml: stream = "STREAM_TEST1@\PDS_PVOB"
		 * component = "COMPONENT_TEST1@\PDS_PVOB" Level to poll = "INITIAL"
		 */

		String jobname = build.getParent().getDisplayName();
		if (!compRevCalled) {
			if (!baselinesToBuild(jobname)) {
				hudsonOut.println(pollMsgs);
				pollMsgs = "";
				return false;
			}
		}
		hudsonOut.println(pollMsgs);
		pollMsgs = "";

		String buildno = String.valueOf(build.getNumber());
		hudsonOut.println("Creating tag. Jobname: " + jobname
				+ ". Buildnumber: " + buildno + ". Status: In progress");
		Tag tag = bl.CreateTag("hudson", jobname, build.getTimestampString2(),
				"inprogress");
		tag.SetEntry("buildno", buildno);
		tag = tag.Persist();

		try {
			// Maybe this code: SnapshotView sv = SnapshotView.Create(st,
			// "viewtag", workspace.);
			// TODO Set up workspace with snapshot and loadmodules
			// mk workspace - that is - Ask 'backend' to do so with workspace
			// (Filepath from constructor), baseline, loadrules
		} catch (Exception e) {
			hudsonOut
					.println("Could not make workspace. Marking baseline with:");
			tag.SetEntry("buildstatus", "couldNotCreateSnapshot");
			tag = tag.Persist();
			hudsonOut.println(tag.Stringify());
			return false;
		}
		hudsonOut.println("Marking baseline with: \n" + tag.Stringify());

		BaselineDiff changes = bl.GetDiffs();
		hudsonOut.println(changes.size() + " elements changed");

		compRevCalled = false; // ~ is set to false here so it is possible to
								// build manually next time and not ask twice if
								// there are new baselines
		return writeChangelog(changelogFile, changes, hudsonOut);
	}

	/**
	 * This method is used by {@link <public boolean checkout(AbstractBuild
	 * build, Launcher launcher, FilePath workspace, BuildListener listener,
	 * File changelogFile) throws IOException, InterruptedException>
	 * [checkout()]} to write the changelog used uses.
	 * 
	 * @param changelogFile
	 *            The file given by Hudson.
	 * @param changes
	 *            The list of changes to be written as XML.
	 * @return true if the changelog was persisted, false if not.
	 * @throws IOException
	 */
	private boolean writeChangelog(File changelogFile, BaselineDiff changes,
			PrintStream hudsonOut) throws IOException {
		logger.trace_function();
		String ls = System.getProperty("line.seperator");

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		// Here the .hudson/jobs/[project name]/changelog.xml is written

		hudsonOut.print("Writing Hudson changelog...");
		try {
			baos.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".getBytes());
			baos.write("<changelog>".getBytes());

			baos.write("<changeset>".getBytes());
			String temp;
			baos.write("<entry>".getBytes());
			baos.write(("<blName>" + bl.GetShortname() + "</blName>")
					.getBytes());
			for (Activity act : changes) {
				baos.write("<activity>".getBytes());
				baos.write(("<actName>" + act.GetShortname() + "</actName>")
						.getBytes());
				baos.write(("<author>Hudson" + act.GetUser() + "</author>")
						.getBytes());
				List<Version> versions = act.changeset.versions;
				for (Version v : versions) {
					temp = "<file>" + v.toString() + "</file>";// v.GetDate()+" "
																// +
																// v.GetFilename()
																// + "."
																// +v.GetRevision()+
																// " " +
																// v.GetUser()/*getv.getBlame*/+" "
																// +
																// /*v.getMessage()*/"</file>";
					baos.write(temp.getBytes());
				}
				baos.write("</activity>".getBytes());
			}
			baos.write("</entry>".getBytes());
			baos.write("</changeset>".getBytes());

			baos.write("</changelog>".getBytes());
			FileOutputStream fos = new FileOutputStream(changelogFile);
			fos.write(baos.toByteArray());
			fos.close();
			hudsonOut.println(" DONE");
			return true;
		} catch (Exception e) {
			// If the changelog cannot be written, the baseline will not be
			// build
			hudsonOut.println(" FAILED");
			logger.log("Changelog failed with " + e.getMessage());
			return false;
		}
	}

	@Override
	public ChangeLogParser createChangeLogParser() {
		logger.trace_function();
		return new ChangeLogParserImpl();
	}

	/**
	 * Currently this method returns BUILD_NOW, but later it should evaluate IF
	 * Hudson should build.
	 */
	@Override
	public PollingResult compareRemoteRevisionWith(
			AbstractProject<?, ?> project, Launcher launcher,
			FilePath workspace, TaskListener listener, SCMRevisionState baseline)
			throws IOException, InterruptedException {
		logger.trace_function();
		SCMRevisionStateImpl scmState = (SCMRevisionStateImpl) baseline;
		PrintStream hudsonOut = listener.getLogger();

		if (baselinesToBuild(scmState.getJobname())) {
			compRevCalled = true;
			return PollingResult.BUILD_NOW;
		}
		return PollingResult.NO_CHANGES;
	}

	@Override
	public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build,
			Launcher launcher, TaskListener listener) throws IOException,
			InterruptedException {
		logger.trace_function();
		PrintStream hudsonOut = listener.getLogger();

		if (bl == null) {
			return null;
		}

		SCMRevisionStateImpl scmRS = new SCMRevisionStateImpl(build.getParent()
				.getDisplayName(), String.valueOf(build.getNumber()));
		return scmRS;
	}

	private boolean baselinesToBuild(String jobname) {
		logger.trace_function();
		Component co = null;
		st = null;
		try {
			co = UCMEntity.GetComponent("component:" + component);
			st = UCMEntity.GetStream("stream:" + stream);
		} catch (UCMEntityException ucmEe) {
			pollMsgs += ucmEe.toString() + "\n";
			return false;
		}

		pollMsgs += "Getting "
				+ (newerThanRecommended ? "baselines newer than the recomended baseline "
						: "all baselines ") + "for " + component + " and "
				+ stream + " on promotionlevel " + levelToPoll + "\n";

		try {
			baselines = co.GetBaselines(st,
					UCMEntity.Plevel.valueOf(levelToPoll));
		} catch (Exception e) {
			pollMsgs += "Could not retrieve baselines from repository\n";
			return false;
		}

		// Remove baselines that have buildInProgress - this is relevant if
		// several builds are run at the same time on the same Hudson-job
		TagQuery tq = new TagQuery();
		tq.AddCondition("buildstatus", "^(?!inprogress$)");

		// Filter so only baselines from this job is in the list
		baselines = baselines.Filter(tq, "hudson", jobname);

		if (baselines.size() > 0) {
			pollMsgs += "Retrieved baselines:\n";
			for (Baseline b : baselines)
				pollMsgs += b.GetShortname() + "\n";
			if (newest) {
				bl = baselines.get(0);
				pollMsgs += "Building newest baseline: " + bl + "\n";
			} else {
				bl = baselines.get(baselines.size() - 1);
				pollMsgs += "Building next baseline: " + bl + "\n";
			}
		} else {
			pollMsgs += "No baselines on chosen parameters.\n";
			return false;
		}
		return true;
	}

	/**
	 * This method is used by Hudson to load persisted data when users enter the
	 * job config page.
	 * 
	 * @return
	 */
	public String getLevelToPoll() {
		logger.trace_function();
		return levelToPoll;
	}

	/**
	 * This method is used by Hudson to load persisted data when users enter the
	 * job config page.
	 * 
	 * @return component
	 */
	public String getComponent() {
		logger.trace_function();
		return component;
	}

	/**
	 * This method is used by Hudson to load persisted data when users enter the
	 * job config page.
	 * 
	 * @return stream
	 */
	public String getStream() {
		logger.trace_function();
		return stream;
	}

	/**
	 * This method is used by Hudson to load persisted data when users enter the
	 * job config page.
	 * 
	 * @return loadModule - which can be "all" or "modifiable"
	 */
	public String getLoadModule() {
		logger.trace_function();
		return loadModule;
	}

	/**
	 * This method is used by CC4HNotifier.perform for tagging the baseline
	 * after build
	 * 
	 * @return baseline
	 */
	public Baseline getBaseline() {
		logger.trace_function();
		return bl;
	}

	/**
	 * This method is used by Hudson to load persisted data when users enter the
	 * job config page.
	 * 
	 * @return newest - whether the user wants the newest or the latest baseline
	 */
	public boolean isNewest() {
		logger.trace_function();
		return newest;
	}

	/**
	 * This method is used by Hudson to load persisted data when users enter the
	 * job config page.
	 * 
	 * @return newerThanRecommended - whether the user only wants baselines
	 *         newer than recommended
	 */
	public boolean isNewerThanRecommended() {
		logger.trace_function();
		return newerThanRecommended;
	}

	/**
	 * This class is used to describe the plugin to Hudson
	 * 
	 * @author Troels Selch S�rensen
	 * @author Margit Bennetzen
	 * 
	 */
	@Extension
	public static class PucmScmDescriptor extends SCMDescriptor<PucmScm> {

		private String cleartool;
		private List<String> levels;
		private List<String> loadModules;

		public PucmScmDescriptor() {
			super(PucmScm.class, null);
			logger.trace_function();
			levels = getLevels();
			loadModules = getLoadModules();
			load(); // load() MUST be called to get persisted data (check out
					// save() as well)
		}

		/**
		 * This method is called, when the user saves the global Hudson
		 * configuration.
		 * 
		 */
		@Override
		public boolean configure(org.kohsuke.stapler.StaplerRequest req,
				JSONObject json) throws FormException {
			logger.trace_function();
			cleartool = req.getParameter("cc4h.cleartool").trim();
			save();
			// If no exception has been thrown at this point, then it's safe to
			// return true
			return true;
		}

		/**
		 * This is called by Hudson to discover the plugin name
		 */
		@Override
		public String getDisplayName() {
			logger.trace_function();
			return "Praqmatic UCM";
		}

		/**
		 * This method is called by the scm/Pucm/global.jelly to validate the
		 * input without reloading the global configuration page
		 * 
		 * @param value
		 * @return
		 */
		public FormValidation doExecutableCheck(@QueryParameter String value) {
			logger.trace_function();
			return FormValidation.validateExecutable(value);
		}

		/**
		 * Called by Hudson. If the user does not input a command for Hudson to
		 * use when polling, default value is returned
		 * 
		 * @return
		 */
		public String getCleartool() {
			logger.trace_function();
			if (cleartool == null || cleartool.equals(""))
				return "cleartool";
			return cleartool;
		}

		/**
		 * Used by Hudson to display a list of valid promotionlevels to build
		 * from
		 * 
		 * @return
		 */
		public List<String> getLevels() {
			logger.trace_function();
			levels = new ArrayList<String>();
			levels.add("INITIAL");
			levels.add("BUILT");
			levels.add("TESTED");
			levels.add("RELEASED");
			levels.add("REJECTED");
			return levels;
		}

		/**
		 * Used by Hudson to display a list of loadModules (whether to poll all
		 * or only modifiable elements
		 * 
		 * @return
		 */
		public List<String> getLoadModules() {
			logger.trace_function();
			loadModules = new ArrayList<String>();
			loadModules.add("All");
			loadModules.add("Modifiable");
			return loadModules;
		}
	}
}
