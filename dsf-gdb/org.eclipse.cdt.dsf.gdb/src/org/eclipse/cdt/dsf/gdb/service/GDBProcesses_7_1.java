/*******************************************************************************
 * Copyright (c) 2010, 2015 Ericsson and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Ericsson - initial API and implementation
 *     Andy Jin (QNX) - Not output thread osId as a string when it is null (Bug 397039)
 *     Alvaro Sanchez-Leon - Bug 451396 - Improve extensibility to process MI "-thread-info" results
 *     Simon Marchi (Ericsson) - Bug 378154 - Pass thread name from MIThread to the data model
 *     Marc Khouzam (Ericsson) - Support for exited processes in the debug view (bug 407340)
 *******************************************************************************/
package org.eclipse.cdt.dsf.gdb.service;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.ImmediateDataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.ImmediateRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.Immutable;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IRunControl.IContainerResumedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl.IContainerSuspendedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl.IExitedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl.IStartedDMEvent;
import org.eclipse.cdt.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.cdt.dsf.debug.service.command.BufferedCommandControl;
import org.eclipse.cdt.dsf.debug.service.command.CommandCache;
import org.eclipse.cdt.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.cdt.dsf.gdb.internal.GdbPlugin;
import org.eclipse.cdt.dsf.gdb.service.command.IGDBControl;
import org.eclipse.cdt.dsf.mi.service.IMICommandControl;
import org.eclipse.cdt.dsf.mi.service.IMIProcessDMContext;
import org.eclipse.cdt.dsf.mi.service.command.CommandFactory;
import org.eclipse.cdt.dsf.mi.service.command.output.MIListThreadGroupsInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIListThreadGroupsInfo.IThreadGroupInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIThread;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * This class implements the IProcesses interface for GDB 7.1
 * which provides new information about cores for threads and processes.
 *
 * @since 4.0
 */
public class GDBProcesses_7_1 extends GDBProcesses_7_0 {

	@Immutable
	protected static class MIThreadDMData_7_1 extends MIThreadDMData implements IGdbThreadDMData {
		final String[] fCores;

		public MIThreadDMData_7_1(String name, String id, String[] cores) {
			super(name, id);
			fCores = cores;
		}

		@Override
		public String[] getCores() {
			return fCores;
		}

		@Override
		public String getOwner() {
			return null;
		}
	}

	private CommandFactory fCommandFactory;
	// This cache is used when we send command to get the cores.
	// The value of the cores can change at any time, but we provide
	// an updated value whenever there is a suspended event.
	private CommandCache fCommandForCoresCache;
	private IGDBControl fCommandControl;

	public GDBProcesses_7_1(DsfSession session) {
		super(session);
	}

	@Override
	public void initialize(final RequestMonitor requestMonitor) {
		super.initialize(new ImmediateRequestMonitor(requestMonitor) {
			@Override
			protected void handleSuccess() {
				doInitialize(requestMonitor);
			}
		});
	}

	/**
	 * This method initializes this service after our superclass's initialize()
	 * method succeeds.
	 *
	 * @param requestMonitor
	 *            The call-back object to notify when this service's
	 *            initialization is done.
	 */
	private void doInitialize(RequestMonitor requestMonitor) {
		fCommandControl = getServicesTracker().getService(IGDBControl.class);

		// This caches stores the result of a command when received; also, this cache
		// is manipulated when receiving events.  Currently, events are received after
		// three scheduling of the executor, while command results after only one.  This
		// can cause problems because command results might be processed before an event
		// that actually arrived before the command result.
		// To solve this, we use a bufferedCommandControl that will delay the command
		// result by two scheduling of the executor.
		// See bug 280461
		fCommandForCoresCache = new CommandCache(getSession(),
				new BufferedCommandControl(fCommandControl, getExecutor(), 2));
		fCommandForCoresCache.setContextAvailable(fCommandControl.getContext(), true);

		fCommandFactory = getServicesTracker().getService(IMICommandControl.class).getCommandFactory();
		getSession().addServiceEventListener(this, null);

		requestMonitor.done();
	}

	@Override
	public void shutdown(RequestMonitor requestMonitor) {
		getSession().removeServiceEventListener(this);

		super.shutdown(requestMonitor);
	}

	@Override
	public void getExecutionData(final IThreadDMContext dmc, final DataRequestMonitor<IThreadDMData> rm) {
		if (dmc instanceof IMIProcessDMContext) {
			// Starting with GDB 7.1, we can obtain the list of cores a process is currently
			// running on (each core that has a thread of that process).
			// We have to use -list-thread-groups to obtain that information
			// Note that -list-thread-groups does not show the 'user' field
			super.getExecutionData(dmc, new ImmediateDataRequestMonitor<IThreadDMData>(rm) {
				@Override
				protected void handleSuccess() {
					final IThreadDMData firstLevelData = getData();

					// No need to go further if we are dealing with an exited process
					if (firstLevelData instanceof IGdbThreadExitedDMData) {
						rm.done(firstLevelData);
						return;
					}

					ICommandControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc,
							ICommandControlDMContext.class);
					final String groupId = getGroupFromPid(((IMIProcessDMContext) dmc).getProcId());

					fCommandForCoresCache.execute(fCommandFactory.createMIListThreadGroups(controlDmc),
							new ImmediateDataRequestMonitor<MIListThreadGroupsInfo>(rm) {
								@Override
								protected void handleCompleted() {
									String[] cores = null;
									if (isSuccess()) {
										IThreadGroupInfo[] groups = getData().getGroupList();
										if (groups != null) {
											for (IThreadGroupInfo group : groups) {
												if (group.getGroupId().equals(groupId)) {
													cores = group.getCores();
													break;
												}
											}
										}
									}
									rm.setData(new MIThreadDMData_7_1(firstLevelData.getName(), firstLevelData.getId(),
											cores));
									rm.done();
								}
							});
				}
			});
		} else if (dmc instanceof MIThreadDMC) {
			ICommandControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc, ICommandControlDMContext.class);
			final String groupId = getGroupFromPid(
					(DMContexts.getParentOfType(dmc, IMIProcessDMContext.class)).getProcId());
			String threadId = ((MIThreadDMC) dmc).getId();

			fCommandForCoresCache.execute(fCommandFactory.createMIListThreadGroups(controlDmc, groupId),
					new ImmediateDataRequestMonitor<MIListThreadGroupsInfo>(rm) {
						@Override
						protected void handleCompleted() {
							IThreadDMData threadData = null;
							if (isSuccess()) {
								MIThread[] threads = getData().getThreadInfo().getThreadList();
								if (threads != null) {
									for (MIThread thread : threads) {
										if (thread.getThreadId().equals(threadId)) {
											threadData = createThreadDMData(thread);
											break;
										}
									}
								}
							}

							if (threadData != null) {
								rm.setData(threadData);
							} else {
								rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INVALID_HANDLE,
										"Could not get thread info", getStatus().getException())); //$NON-NLS-1$
							}
							rm.done();
						}
					});
		} else {
			rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid DMC type", null)); //$NON-NLS-1$
			rm.done();
		}
	}

	/**
	 * @since 4.6
	 */
	protected IGdbThreadDMData createThreadDMData(MIThread thread) {
		String id = ""; //$NON-NLS-1$

		if (thread.getOsId() != null) {
			id = thread.getOsId();
		}
		// append thread details (if any) to the thread ID
		// as for GDB 6.x with CLIInfoThreadsInfo#getOsId()
		final String details = thread.getDetails();
		if (details != null && !details.isEmpty()) {
			if (!id.isEmpty())
				id += " "; //$NON-NLS-1$
			id += "(" + details + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		}
		// We must indicate and empty id by using null
		if (id.isEmpty())
			id = null;

		String name = thread.getName();
		String core = thread.getCore();
		return new MIThreadDMData_7_1(name == null ? "" : name, id, core == null ? null : new String[] { core }); //$NON-NLS-1$
	}

	@DsfServiceEventHandler
	public void eventDispatched_7_1(IResumedDMEvent e) {
		if (e instanceof IContainerResumedDMEvent) {
			// This will happen in all-stop mode
			fCommandForCoresCache.setContextAvailable(e.getDMContext(), false);
		} else {
			// This will happen in non-stop mode
			// Keep target available for Container commands
		}
	}

	// Something has suspended, core allocation could have changed
	// during the time it was running.
	@DsfServiceEventHandler
	public void eventDispatched_7_1(ISuspendedDMEvent e) {
		if (e instanceof IContainerSuspendedDMEvent) {
			// This will happen in all-stop mode
			fCommandForCoresCache.setContextAvailable(fCommandControl.getContext(), true);
		} else {
			// This will happen in non-stop mode
		}

		fCommandForCoresCache.reset();
	}

	// Event handler when a thread or threadGroup starts, core allocation
	// could have changed
	@DsfServiceEventHandler
	public void eventDispatched_7_1(IStartedDMEvent e) {
		fCommandForCoresCache.reset();
	}

	// Event handler when a thread or a threadGroup exits, core allocation
	// could have changed
	@DsfServiceEventHandler
	public void eventDispatched_7_1(IExitedDMEvent e) {
		fCommandForCoresCache.reset();
	}

	@Override
	public void flushCache(IDMContext context) {
		fCommandForCoresCache.reset(context);
		super.flushCache(context);
	}
}
