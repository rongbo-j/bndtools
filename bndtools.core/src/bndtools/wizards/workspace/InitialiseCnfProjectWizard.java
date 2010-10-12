package bndtools.wizards.workspace;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.IShellProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

import bndtools.Plugin;
import bndtools.tasks.repo.LocalRepositoryTasks;
import bndtools.utils.SWTConcurrencyUtil;

public class InitialiseCnfProjectWizard extends Wizard implements IImportWizard {

	private IWorkbench workbench;
	private IStructuredSelection selection;

	public InitialiseCnfProjectWizard() {
	    setNeedsProgressMonitor(true);
	}

    /**
     * Show the wizard if it needs to be shown (i.e. the cnf project does not
     * exist and the preference to show the wizard has not been disabled). This
     * method is safe to call from a non-UI thread.
     *
     * @param ignorePreference
     *            Shows the dialog even if the preference setting
     *            ("hideInitialiseCnfWizard") has been set to true.
     * @param asyncExec
     *            Controls whether the wizard opening is performed as an
     *            asynchronous execution on the display thread; ignored if this
     *            method is invoked from the UI thread itself.
     *
     * @return Whether the wizard was shown and finished successfully.
     */
	public static boolean showIfNeeded(boolean ignorePreference, boolean asyncExec, final IShellProvider shellProvider) {
	    IPreferenceStore store = Plugin.getDefault().getPreferenceStore();
	    boolean hideWizard = store.getBoolean(Plugin.PREF_HIDE_INITIALISE_CNF_WIZARD);
	    if(hideWizard && !ignorePreference) {
	        return false;
	    }

	    final InitialiseCnfProjectWizard wizard;

	    if (!LocalRepositoryTasks.isBndWorkspaceConfigured()) {
            wizard = new InitialiseCnfProjectWizard();
            wizard.addPage(new InitialiseCnfProjectIntroWizardPage("message", Messages.InitialiseCnfProjectIntroWizardPage_createCnfMessage));
	    } else if (!LocalRepositoryTasks.isRepositoryUpToDate()) {
	        wizard = new InitialiseCnfProjectWizard();
	        wizard.addPage(new UpdateRepositoryWizardPage());
	    } else {
	        wizard = null;
	    }

	    final AtomicBoolean shownOkay = new AtomicBoolean(false);
	    if(wizard != null) {
	        Display display = PlatformUI.getWorkbench().getDisplay();
	        SWTConcurrencyUtil.execForDisplay(display, asyncExec, new Runnable() {
	            public void run() {
	                // Modified wizard dialog -- change "Finish" to "OK"
	                WizardDialog dialog = new WizardDialog(shellProvider.getShell(), wizard) {
	                    @Override
	                    protected Button createButton(Composite parent, int id, String label, boolean defaultButton) {
	                        if (id == IDialogConstants.FINISH_ID)
	                            label = IDialogConstants.OK_LABEL;
	                        return super.createButton(parent, id, label, defaultButton);
	                    }
	                };
	                shownOkay.set(dialog.open() == Window.OK);
	            }
	        });
	    }

	    return shownOkay.get();
	}

	public void init(IWorkbench workbench, IStructuredSelection selection) {
		this.workbench = workbench;
		this.selection = selection;
	}

	@Override
	public boolean performFinish() {
	    try {
	        final MultiStatus status = new MultiStatus(Plugin.PLUGIN_ID, 0, "Problems occurred while configuring the Bnd workspace.", null);
	        final IWorkspaceRunnable workspaceOp = new IWorkspaceRunnable() {
                public void run(IProgressMonitor monitor) throws CoreException {
                    SubMonitor progress = SubMonitor.convert(monitor, "Copying files to repository...", 4);

                    LocalRepositoryTasks.configureBndWorkspace(progress.newChild(1, 0));
                    LocalRepositoryTasks.installImplicitRepositoryContents(status, progress.newChild(2, 0));
                    LocalRepositoryTasks.refreshWorkspaceForRepository(progress.newChild(1, 0));
                }
            };
            getContainer().run(false, false, new IRunnableWithProgress() {
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        ResourcesPlugin.getWorkspace().run(workspaceOp, monitor);
                    } catch (CoreException e) {
                        throw new InvocationTargetException(e);
                    }
                }
            });

            if(!status.isOK())
                ErrorDialog.openError(getShell(), "Warning", null, status);

            return true;
        } catch (InvocationTargetException e) {
            ErrorDialog.openError(getShell(), "Error", null, new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error creating workspace configuration project.", e.getCause()));
        } catch (InterruptedException e) {
            // Can't happen?
        }
        return false;
	}

	@Override
	public boolean performCancel() {
	    IPreferenceStore store = Plugin.getDefault().getPreferenceStore();
	    boolean hide = store.getBoolean(Plugin.PREF_HIDE_INITIALISE_CNF_ADVICE);

	    if(!hide) {
	        MessageDialogWithToggle dialog = MessageDialogWithToggle.openInformation(getShell(), Messages.InitialiseCnfProjectWizard_info_dialog_popup, Messages.InitialiseCnfProjectWizard_info_dialog_message, Messages.InitialiseCnfProjectWizard_info_dialog_donotshow, false, null, null);
	        if(dialog.getToggleState()) {
	            store.setValue(Plugin.PREF_HIDE_INITIALISE_CNF_ADVICE, true);
	        }
	    }
	    return super.performCancel();
	}
}