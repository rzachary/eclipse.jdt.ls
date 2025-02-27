/*******************************************************************************
 * Copyright (c) 2016-2019 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *     Microsoft Corporation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.managers;

import static java.util.Arrays.asList;
import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;
import static org.eclipse.jdt.ls.core.internal.ResourceUtils.isContainedIn;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ISaveContext;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.launching.AbstractVMInstall;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.ActionableNotification;
import org.eclipse.jdt.ls.core.internal.IConstants;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.JobHelpers;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.preferences.IPreferencesChangeListener;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences.FeatureStatus;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WatchKind;

public class StandardProjectsManager extends ProjectsManager {
	protected static final String BUILD_SUPPORT_EXTENSION_POINT_ID = "buildSupport";
	private static final Set<String> watchers = new LinkedHashSet<>();
	private PreferenceManager preferenceManager;
	//@formatter:off
	private static final List<String> basicWatchers = Arrays.asList(
			"**/*.java",
			"**/.project",
			"**/.classpath",
			"**/.settings/*.prefs",
			"**/src/**"
	);
	//@formatter:on

	private Job registerWatcherJob = new Job("Register Watchers") {

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			JobHelpers.waitForJobsToComplete();
			registerWatchers();
			return Status.OK_STATUS;
		}

	};

	private IPreferencesChangeListener preferenceChangeListener = null;

	public StandardProjectsManager(PreferenceManager preferenceManager) {
		super(preferenceManager);
		this.preferenceManager = preferenceManager;
	}

	@Override
	public void cleanInvalidProjects(final Collection<IPath> rootPaths, IProgressMonitor monitor) {
		SubMonitor subMonitor = SubMonitor.convert(monitor, 20);
		deleteInvalidProjects(rootPaths, subMonitor.split(10));
		GradleBuildSupport.cleanGradleModels(subMonitor.split(10));
	}

	private void deleteInvalidProjects(Collection<IPath> rootPaths, IProgressMonitor monitor) {
		List<String> workspaceProjects = rootPaths.stream().map((IPath rootPath) -> ProjectUtils.getWorkspaceInvisibleProjectName(rootPath)).collect(Collectors.toList());
		ArrayList<IProject> deleteProjectCandates = new ArrayList<>();
		for (IProject project : ProjectUtils.getAllProjects()) {
			if (project.equals(getDefaultProject())) {
				continue;
			}

			boolean hasSpecificDeleteProjectLogic = false;
			Optional<IBuildSupport> buildSupportOpt = BuildSupportManager.find(project);
			if (buildSupportOpt.isPresent()) {
				hasSpecificDeleteProjectLogic = buildSupportOpt.get().hasSpecificDeleteProjectLogic();
			}

			if (project.exists() && (ResourceUtils.isContainedIn(project.getLocation(), rootPaths) || workspaceProjects.contains(project.getName()) || hasSpecificDeleteProjectLogic)) {
				try {
					project.getDescription();
					if (hasSpecificDeleteProjectLogic) {
						deleteProjectCandates.add(project);
					}
				} catch (CoreException e) {
					try {
						project.delete(true, monitor);
					} catch (CoreException e1) {
						JavaLanguageServerPlugin.logException(e1.getMessage(), e1);
					}
				}
			} else {
				try {
					project.delete(false, true, monitor);
				} catch (CoreException e1) {
					JavaLanguageServerPlugin.logException(e1.getMessage(), e1);
				}
			}
		}

		BuildSupportManager.obtainBuildSupports().stream().filter(IBuildSupport::hasSpecificDeleteProjectLogic).forEach(buildSupport -> buildSupport.deleteInvalidProjects(rootPaths, deleteProjectCandates, monitor));

	}

	@Override
	public void fileChanged(String uriString, CHANGE_TYPE changeType) {
		if (uriString == null) {
			return;
		}
		String settingsUrl = preferenceManager.getPreferences().getSettingsUrl();
		if (settingsUrl != null && JavaLanguageServerPlugin.getInstance().getProtocol() != null) {
			URI uri = JDTUtils.toURI(uriString);
			List<URI> uris = getURIs(settingsUrl);
			boolean changed = false;
			for (URI settingsURI : uris) {
				if (settingsURI.equals(uri)) {
					changed = true;
					break;
				}
			}
			if (changed) {
				if (changeType == CHANGE_TYPE.DELETED || changeType == CHANGE_TYPE.CREATED) {
					registerWatchers();
				}
				configureSettings(preferenceManager.getPreferences());
			}
		}
		String formatterUrl = preferenceManager.getPreferences().getFormatterUrl();
		if (formatterUrl != null && JavaLanguageServerPlugin.getInstance().getProtocol() != null) {
			URI uri = JDTUtils.toURI(uriString);
			List<URI> uris = getURIs(formatterUrl);
			boolean changed = false;
			for (URI formatterUri : uris) {
				if (formatterUri.equals(uri)) {
					changed = true;
					break;
				}
			}
			if (changed) {
				if (changeType == CHANGE_TYPE.DELETED || changeType == CHANGE_TYPE.CREATED) {
					registerWatchers();
				}
				FormatterManager.configureFormatter(preferenceManager.getPreferences());
			}
		}
		IResource resource = JDTUtils.getFileOrFolder(uriString);
		if (resource == null) {
			return;
		}
		try {
			Optional<IBuildSupport> bs = getBuildSupport(resource.getProject());
			if (bs.isPresent()) {
				IBuildSupport buildSupport = bs.get();
				boolean requireConfigurationUpdate = buildSupport.fileChanged(resource, changeType, new NullProgressMonitor());
				if (requireConfigurationUpdate) {
					FeatureStatus status = preferenceManager.getPreferences().getUpdateBuildConfigurationStatus();
					switch (status) {
						case automatic:
							// do not force the build, because it's not started by user and should be done only if build file has changed
							updateProject(resource.getProject(), false);
							break;
						case disabled:
							break;
						default:
							if (client != null) {
								String cmd = "java.projectConfiguration.status";
								TextDocumentIdentifier uri = new TextDocumentIdentifier(uriString);
								ActionableNotification updateProjectConfigurationNotification = new ActionableNotification().withSeverity(MessageType.Info)
										.withMessage("A build file was modified. Do you want to synchronize the Java classpath/configuration?").withCommands(asList(new Command("Never", cmd, asList(uri, FeatureStatus.disabled)),
												new Command("Now", cmd, asList(uri, FeatureStatus.interactive)), new Command("Always", cmd, asList(uri, FeatureStatus.automatic))));
								client.sendActionableNotification(updateProjectConfigurationNotification);
							}
					}
				}
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("Problem refreshing workspace", e);
		}
	}

	public static void configureSettings(Preferences preferences) {
		URI settingsUri = preferences.getSettingsAsURI();
		Properties properties = null;
		if (settingsUri != null) {
			try (InputStream inputStream = settingsUri.toURL().openStream()) {
				properties = new Properties();
				properties.load(inputStream);
			} catch (Exception e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
				return;
			}
		}
		initializeDefaultOptions(preferences);
		Hashtable<String, String> javaOptions = JavaCore.getOptions();
		if (properties != null && !properties.isEmpty()) {
			properties.forEach((k, v) -> {
				if (k instanceof String && v instanceof String) {
					k = ((String) k).replace("/instance/", "");
					if (javaOptions.get(k) != null) {
						javaOptions.put((String) k, (String) v);
					}
				}
			});
		}
		JavaCore.setOptions(javaOptions);
		new WorkspaceJob("Clean workspace...") {

			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
				ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
				return Status.OK_STATUS;
			}
		}.schedule();
	}

	private static void initializeDefaultOptions(Preferences preferences) {
		Hashtable<String, String> defaultOptions = JavaCore.getDefaultOptions();
		IVMInstall defaultVM = JavaRuntime.getDefaultVMInstall();
		if (defaultVM instanceof AbstractVMInstall) {
			AbstractVMInstall jvm = (AbstractVMInstall) defaultVM;
			long jdkLevel = CompilerOptions.versionToJdkLevel(jvm.getJavaVersion());
			String compliance = CompilerOptions.versionFromJdkLevel(jdkLevel);
			JavaCore.setComplianceOptions(compliance, defaultOptions);
		} else {
			JavaCore.setComplianceOptions(JavaCore.VERSION_11, defaultOptions);
		}
		JavaCore.setOptions(defaultOptions);
		PreferenceManager.initialize();
		preferences.updateTabSizeInsertSpaces(defaultOptions);
	}

	@Override
	public boolean isBuildFile(IResource resource) {
		return buildSupports().filter(bs -> bs.isBuildFile(resource)).findAny().isPresent();
	}

	@Override
	public Optional<IBuildSupport> getBuildSupport(IProject project) {
		return buildSupports().filter(bs -> bs.applies(project)).findFirst();
	}

	protected Stream<IBuildSupport> buildSupports() {
		Map<Integer, IBuildSupport> supporters = new TreeMap<>();
		IExtensionPoint extensionPoint = Platform.getExtensionRegistry().getExtensionPoint(IConstants.PLUGIN_ID, BUILD_SUPPORT_EXTENSION_POINT_ID);
		IConfigurationElement[] configs = extensionPoint.getConfigurationElements();
		for (IConfigurationElement config : configs) {
			try {
				Integer order = Integer.valueOf(config.getAttribute("order"));
				supporters.put(order, (IBuildSupport) config.createExecutableExtension("class")); //$NON-NLS-1$
			} catch (CoreException e) {
				JavaLanguageServerPlugin.logError(config.getAttribute("class") + " implementation was skipped \n" + e.getStatus());
			}
		}
		return supporters.values().stream();
	}

	@Override
	public boolean isBuildLikeFileName(String fileName) {
		return buildSupports().filter(bs -> bs.isBuildLikeFileName(fileName)).findAny().isPresent();
	}

	@Override
	public void prepareToSave(ISaveContext context) throws CoreException {
		if (context.getKind() == ISaveContext.FULL_SAVE) {
			GradleBuildSupport.saveModels();
		}
	}

	@Override
	public void registerWatchers(boolean runInJob) {
		if (runInJob) {
			registerWatcherJob.schedule();
		} else {
			registerWatchers();
		}
	}

	@Override
	public List<FileSystemWatcher> registerWatchers() {
		logInfo(">> registerWatchers'");
		if (preferenceManager.getClientPreferences().isWorkspaceChangeWatchedFilesDynamicRegistered()) {
			Set<String> patterns = new LinkedHashSet<>(basicWatchers);
			buildSupports().forEach(e -> e.getWatchPatterns().forEach(patterns::add));
			Set<IPath> sources = new HashSet<>();
			IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
			try {
				for (IProject project : projects) {
					if (DEFAULT_PROJECT_NAME.equals(project.getName())) {
						continue;
					}
					IJavaProject javaProject = JavaCore.create(project);
					if (javaProject != null && javaProject.exists()) {
						IClasspathEntry[] classpath = javaProject.getRawClasspath();
						for (IClasspathEntry entry : classpath) {
							if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
								IPath path = entry.getPath();
								if (path != null && !path.toString().contains("/src/") && !path.toString().endsWith("/src")) {
									IFolder folder = ResourcesPlugin.getWorkspace().getRoot().getFolder(path);
									if (folder.exists() && !folder.isDerived()) {
										IPath location = folder.getLocation();
										if (location != null && !isContainedIn(location, sources)) {
											sources.add(location);
										}
									}

								}
							}
							if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
								IPath path = entry.getPath();
								IFile resource = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
								if (resource != null && !resource.isDerived()) {
									IPath location = resource.getLocation();
									if (location != null && !isContainedIn(location, sources)) {
										sources.add(location);
									}
								}
							}
						}
						if (!ProjectUtils.isVisibleProject(project)) { // Invisible project will watch referenced libraries' include patterns
							IPath projectFolder = ProjectUtils.getProjectRealFolder(project);
							Set<String> libraries = preferenceManager.getPreferences().getReferencedLibraries().getInclude();
							for (String pattern: libraries) {
								patterns.add(ProjectUtils.resolveGlobPath(projectFolder, pattern).toPortableString());
							}
							patterns.add("**/.settings");
						}
					}
				}
			} catch (JavaModelException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
			List<FileSystemWatcher> fileWatchers = new ArrayList<>();
			patterns.addAll(sources.stream().map(ResourceUtils::toGlobPattern).collect(Collectors.toList()));
			sources.clear();
			URI formatter = preferenceManager.getPreferences().getFormatterAsURI();
			if (formatter == null && preferenceManager.getPreferences().getFormatterUrl() != null) {
				List<URI> uris = getURIs(preferenceManager.getPreferences().getFormatterUrl());
				for (URI uri : uris) {
					addWatcher(uri, sources);
				}
			} else {
				addWatcher(formatter, sources);
			}
			URI settings = preferenceManager.getPreferences().getSettingsAsURI();
			if (settings == null && preferenceManager.getPreferences().getSettingsUrl() != null) {
				List<URI> uris = getURIs(preferenceManager.getPreferences().getSettingsUrl());
				for (URI uri : uris) {
					addWatcher(uri, sources);
				}
			} else {
				addWatcher(settings, sources);
			}
			patterns.addAll(sources.stream().map(p -> ResourceUtils.toGlobPattern(p, false)).collect(Collectors.toList()));
			for (String pattern : patterns) {
				FileSystemWatcher watcher = new FileSystemWatcher(pattern);
				fileWatchers.add(watcher);
			}
			// Watch on project root folders.
			for (IProject project : projects) {
				if (ProjectUtils.isVisibleProject(project) && project.exists()) {
					FileSystemWatcher watcher = new FileSystemWatcher(
						ResourceUtils.toGlobPattern(project.getLocation(), false), WatchKind.Delete);
					fileWatchers.add(watcher);
				}
			}

			if (!patterns.equals(watchers)) {
				logInfo(">> registerFeature 'workspace/didChangeWatchedFiles'");
				DidChangeWatchedFilesRegistrationOptions didChangeWatchedFilesRegistrationOptions = new DidChangeWatchedFilesRegistrationOptions(fileWatchers);
				JavaLanguageServerPlugin.getInstance().unregisterCapability(Preferences.WORKSPACE_WATCHED_FILES_ID, Preferences.WORKSPACE_WATCHED_FILES);
				JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.WORKSPACE_WATCHED_FILES_ID, Preferences.WORKSPACE_WATCHED_FILES, didChangeWatchedFilesRegistrationOptions);
				watchers.clear();
				watchers.addAll(patterns);
			}
			return fileWatchers;
		}
		return Collections.emptyList();
	}

	private List<URI> getURIs(String url) {
		if (url == null) {
			return Collections.emptyList();
		}
		List<URI> result = new ArrayList<>();
		URI uri;
		try {
			uri = new URI(ResourceUtils.toClientUri(url));
		} catch (URISyntaxException e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
			return result;
		}
		if (uri.isAbsolute()) {
			if ("file".equals(uri.getScheme())) {
				url = Path.fromOSString(Paths.get(uri).toString()).toString();
			} else {
				result.add(uri);
				return result;
			}
		}
		if (url.startsWith("/")) {
			uri = new File(url).toURI();
			result.add(uri);
		} else {
			Collection<IPath> rootPaths = preferenceManager.getPreferences().getRootPaths();
			if (rootPaths != null) {
				for (IPath rootPath : rootPaths) {
					File f = new File(rootPath.toOSString(), url);
					result.add(f.toURI());
				}
			}
		}
		return result;
	}

	private void addWatcher(URI uri, Set<IPath> sources) {
		if (uri != null && "file".equals(uri.getScheme())) {
			File file = new File(uri);
			if (file != null) {
				IPath path = new Path(file.getAbsolutePath());
				if (!isContainedIn(path, sources)) {
					sources.add(path);
				}
			}
		}
	}

	@Override
	public void registerListeners() {
		if (this.preferenceChangeListener == null) {
			this.preferenceChangeListener = new IPreferencesChangeListener() {
				@Override
				public void preferencesChange(Preferences oldPreferences, Preferences newPreferences) {
					if (!oldPreferences.getReferencedLibraries().equals(newPreferences.getReferencedLibraries())) {
						registerWatcherJob.schedule(100L);
						UpdateClasspathJob.getInstance().updateClasspath();
					}
					if (!Objects.equals(oldPreferences.getFormatterUrl(), newPreferences.getFormatterUrl()) || !Objects.equals(oldPreferences.getSettingsUrl(), newPreferences.getSettingsUrl())) {
						registerWatcherJob.schedule(100L);
						if (!Objects.equals(oldPreferences.getSettingsUrl(), newPreferences.getSettingsUrl())) {
							configureSettings(newPreferences);
						}
						if (!Objects.equals(oldPreferences.getFormatterUrl(), newPreferences.getFormatterUrl())) {
							FormatterManager.configureFormatter(newPreferences);
						}
					}
					if (!Objects.equals(oldPreferences.getResourceFilters(), newPreferences.getResourceFilters())) {
						try {
							configureFilters(new NullProgressMonitor());
						} catch (CoreException e) {
							JavaLanguageServerPlugin.logException(e.getMessage(), e);
						}
					}
				}
			};
			this.preferenceManager.addPreferencesChangeListener(this.preferenceChangeListener);
		}
		buildSupports().forEach(p -> {
			try {
				p.registerPreferencesChangeListener(this.preferenceManager);
			} catch (CoreException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
		});
	}
}
