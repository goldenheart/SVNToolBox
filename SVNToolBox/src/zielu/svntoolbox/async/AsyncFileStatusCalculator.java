/* 
 * $Id$
 */
package zielu.svntoolbox.async;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.base.Optional;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import zielu.svntoolbox.FileStatus;
import zielu.svntoolbox.FileStatusCalculator;
import zielu.svntoolbox.SvnToolBoxProject;
import zielu.svntoolbox.projectView.ProjectViewManager;
import zielu.svntoolbox.projectView.ProjectViewStatus;
import zielu.svntoolbox.projectView.ProjectViewStatusCache;
import zielu.svntoolbox.util.LogStopwatch;

/**
 * <p></p>
 * <br/>
 * <p>Created on 05.12.13</p>
 *
 * @author Lukasz Zielinski
 */
public class AsyncFileStatusCalculator extends AbstractProjectComponent {
    private final Logger LOG = Logger.getInstance(getClass());

    private final FileStatusCalculator myStatusCalc = new FileStatusCalculator();
    private final BlockingQueue<StatusRequest> myRequestQueue = new LinkedBlockingQueue<StatusRequest>();
    private final Set<VirtualFile> myPendingFiles = new HashSet<VirtualFile>();
    private final Lock myLock = new ReentrantLock();

    private final AtomicBoolean myActive = new AtomicBoolean();
    private final AtomicBoolean myCalculationInProgress = new AtomicBoolean();

    private ProjectViewManager myProjectViewManager;
    private AtomicInteger PV_SEQ;

    public AsyncFileStatusCalculator(Project project) {
        super(project);
    }

    public static AsyncFileStatusCalculator getInstance(@NotNull Project project) {
        return project.getComponent(AsyncFileStatusCalculator.class);
    }

    @Override
    public void initComponent() {
        super.initComponent();
        if (myActive.compareAndSet(false, true)) {
            myProjectViewManager = ProjectViewManager.getInstance(myProject);
            PV_SEQ = SvnToolBoxProject.getInstance(myProject).sequence();
        }
    }

    private void calculateStatus() {
        final boolean DEBUG = LOG.isDebugEnabled();
        if (myActive.get()) {
            if (!myCalculationInProgress.get()) {
                ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
                    @Override
                    public void run() {
                        if (myCalculationInProgress.compareAndSet(false, true)) {
                            boolean exhaused = false;
                            try {
                                StatusRequest request = myRequestQueue.poll(70, TimeUnit.MILLISECONDS);
                                if (request != null) {
                                    myLock.lock();
                                    boolean calculate = myPendingFiles.remove(request.file);
                                    myLock.unlock();
                                    if (calculate) {
                                        AccessToken token = ApplicationManager.getApplication().acquireReadActionLock();                                        
                                        //if we got here we can safely assume file is versioned in svn                                        
                                        try {
                                            LogStopwatch watch = LogStopwatch.debugStopwatch(LOG, "[" + PV_SEQ.incrementAndGet() + "] Status calculation for " + request.file).start();
                                            FileStatus status = myStatusCalc.statusForFileUnderSvn(request.project, request.file);
                                            watch.stop();
                                            ProjectViewStatusCache cache = myProjectViewManager.getStatusCache();
                                            if (status.getBranchName().isPresent()) {
                                                cache.add(request.file, new ProjectViewStatus(status.getBranchName().get()));
                                            } else {
                                                cache.add(request.file, ProjectViewStatus.EMPTY);
                                            }                                            
                                        } finally {
                                            token.finish();                                            
                                        }
                                    } else {
                                        if (DEBUG) {
                                            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] "+request.file.getPath()+" was already calculated");
                                        }
                                    }
                                } else {
                                    if (DEBUG) {
                                        LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Requests exhausted");
                                    }
                                    exhaused = true;                                    
                                }
                            } catch (InterruptedException e) {
                                LOG.error(e);
                            } finally {
                                if (myCalculationInProgress.compareAndSet(true, false)) {
                                    if (exhaused) {
                                        if (DEBUG) {
                                            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Requesting Project View refresh");
                                        }
                                        myProjectViewManager.refreshProjectView(myProject);
                                    } else {
                                        if (DEBUG) {
                                            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Scheduling next status calculation - "+myRequestQueue.size()+" requests pending");
                                        }
                                        calculateStatus();
                                    }
                                }
                            }
                        } else {
                            if (DEBUG) {
                                LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Another status calculation in progress");
                            }
                        }
                    }
                });
            } else {
                if (DEBUG) {
                    LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Another status calculation in progress");
                }
            }
        } else {
            if (DEBUG) {
                LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Component inactive - status calculation cancelled");
            }    
        }
    }

    @Override
    public void projectClosed() {
        if (myActive.compareAndSet(true, false)) {
            myLock.lock();
            int pendingFiles = myPendingFiles.size();
            myPendingFiles.clear();
            myLock.unlock();
            if (LOG.isDebugEnabled()) {
                LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Project closed. Pending: files="+pendingFiles+", requests="+myRequestQueue.size());
            }
            myRequestQueue.clear();
        }
        super.projectClosed();
    }

    @Override
    public void disposeComponent() {
        myRequestQueue.clear();
        int pendingFiles = myPendingFiles.size();
        myPendingFiles.clear();
        if (LOG.isDebugEnabled()) {
            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Component disposed. Pending: files="+pendingFiles+", requests="+myRequestQueue.size());
        }
        super.disposeComponent();
    }

    public Optional<FileStatus> scheduleStatusForFileUnderSvn(@Nullable Project project, @NotNull VirtualFile vFile) {
        if (myActive.get()) {
            if (project == null) {
                return Optional.of(new FileStatus());
            } else {
                if (myActive.get()) {
                    boolean queued = false;
                    myLock.lock();
                    if (myPendingFiles.add(vFile)) {
                        queued = true;
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] " + vFile.getPath()+" already awaits calculation");
                        }
                    }
                    myLock.unlock();
                    if (queued) {
                        myRequestQueue.add(new StatusRequest(project, vFile));
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Queued request for " + vFile.getPath());
                            LOG.debug("[" + PV_SEQ.incrementAndGet() + "] Scheduling on-queued status calculation - "+myRequestQueue.size()+" requests pending");
                        }
                        calculateStatus();
                    }
                }
                return Optional.absent();
            }
        } else {
            return Optional.absent();
        }
    }
}