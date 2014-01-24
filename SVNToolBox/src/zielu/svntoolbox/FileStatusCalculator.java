/* 
 * $Id$
 */
package zielu.svntoolbox;

import java.io.File;
import java.util.Collection;
import java.util.List;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.SvnBranchConfigurationManager;
import org.jetbrains.idea.svn.SvnConfiguration;
import org.jetbrains.idea.svn.SvnStatusUtil;
import org.jetbrains.idea.svn.SvnUtil;
import org.jetbrains.idea.svn.SvnVcs;
import org.jetbrains.idea.svn.branchConfig.SvnBranchConfigurationNew;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.SVNInfo;
import zielu.svntoolbox.util.LogStopwatch;

/**
 * <p></p>
 * <br/>
 * <p>Created on 20.09.13</p>
 *
 * @author Lukasz Zielinski
 */
public class FileStatusCalculator {
    private final Logger LOG = Logger.getInstance(getClass());

    public List<VirtualFile> filterUnderSvn(@NotNull Project project, Collection<VirtualFile> files) {
        List<VirtualFile> result = Lists.newArrayListWithCapacity(files.size());
        for (VirtualFile vFile : files) {
            if (filesUnderSvn(project, vFile)) {
                result.add(vFile);
            }
        }
        return result;
    }

    public boolean filesUnderSvn(@Nullable Project project, VirtualFile... vFiles) {
        if (project == null) {
            return false;
        }
        final SvnVcs svn = SvnVcs.getInstance(project);
        return filesUnderSvn(svn, project, vFiles);
    }

    public boolean filesUnderSvn(@NotNull SvnVcs svn, @Nullable Project project, VirtualFile... vFiles) {
        if (project == null) {
            return false;
        }
        boolean result = ProjectLevelVcsManager.getInstance(project).checkAllFilesAreUnder(svn, vFiles);
        if (result && vFiles != null) {
            for (VirtualFile vFile : vFiles) {
                if (!SvnStatusUtil.isUnderControl(project, vFile)) {
                    return false;
                }
            }
        }
        return result;
    }
    
    @NotNull
    public FileStatus statusForFileUnderSvn(@Nullable Project project, @NotNull VirtualFile vFile) {
        if (project == null) {
            return new FileStatus();
        }
        SvnVcs svn = SvnVcs.getInstance(project);
        return statusForFileUnderSvn(svn, project, vFile);        
    }
    
    @NotNull
    public FileStatus statusFor(@Nullable Project project, @NotNull VirtualFile vFile) {
        if (project == null) {
            return new FileStatus();
        }
        SvnVcs svn = SvnVcs.getInstance(project);
        return statusFor(svn, project, vFile);
    }

    private Optional<VirtualFile> getWCRoot(File currentFile) {
        //TODO: there is also #getWorkingCopyRootNew for Svn 1.8
        File root = SvnUtil.getWorkingCopyRootNew(currentFile);
        if (root == null) {
            LOG.warn("WC root not found for: file="+currentFile.getAbsolutePath());
            return Optional.absent();
        } else {
            VirtualFile rootVf = SvnUtil.getVirtualFile(root.getPath());
            assert rootVf != null: "Root VF not found for: "+root.getPath();
            return Optional.of(rootVf); 
        } 
    }
    
    private Optional<FileStatus> statusForCli(Project project, SVNURL fileUrl, File currentFile) {
        LogStopwatch watch = LogStopwatch.debugStopwatch(LOG, "["+SvnToolBoxProject.getInstance(project).sequence().incrementAndGet()+"] Status For Cli").start();
        Optional<VirtualFile> root = getWCRoot(currentFile);
        watch.tick("WC Root");
        if (root.isPresent()) {
            try {
                SvnBranchConfigurationManager branchManager = SvnBranchConfigurationManager.getInstance(project);
                watch.tick("Root VF by File");
                SvnBranchConfigurationNew branchConfig = branchManager.get(root.get());
                watch.tick("Branch Config");
                String fileUrlPath = fileUrl.toString();
                String baseName = branchConfig.getBaseName(fileUrlPath);
                watch.tick("Base Name");
                watch.stop();
                return Optional.of(new FileStatus(fileUrl, baseName));
            } catch (VcsException e) {
                LOG.error("Could not get branch configuration", e);
            }            
        } else {
            watch.stop();
        }
        return Optional.absent();
    }
    
    private Optional<FileStatus> statusForSvnKit(SVNInfo info, SvnVcs svn, SVNURL fileUrl, File currentFile) {
        Optional<VirtualFile> root = getWCRoot(currentFile);
        if (root.isPresent()) {
            SVNURL branch = SvnUtil.getBranchForUrl(svn, root.get(), fileUrl.toString());
            return Optional.of(new FileStatus(fileUrl, branch));
        }
        return Optional.absent();
    }
    
    @NotNull
    private FileStatus statusForFileUnderSvn(@NotNull SvnVcs svn, @NotNull Project project, @NotNull VirtualFile vFile) {
        File currentFile = new File(vFile.getPath());
        SVNURL fileUrl = SvnUtil.getUrl(svn, currentFile);
        if (fileUrl != null) {
            SVNInfo info = svn.getInfo(vFile);
            if (info != null) {
                SvnConfiguration svnConfig = SvnConfiguration.getInstance(project);
                Optional<FileStatus> status;
                if (svnConfig.isCommandLine()) {
                    status = statusForCli(project, fileUrl, currentFile);                    
                } else {
                    status = statusForSvnKit(info, svn, fileUrl, currentFile);                    
                }
                if (status.isPresent()) {
                    return status.get();
                }
            } else {
                return new FileStatus(fileUrl);
            }
        }
        return new FileStatus();
    }
    
    @NotNull
    public FileStatus statusFor(@NotNull SvnVcs svn, @NotNull Project project, @NotNull VirtualFile vFile) {
        if (filesUnderSvn(svn, project, vFile)) {
            return statusForFileUnderSvn(svn, project, vFile);    
        }
        return new FileStatus();
    }
}
