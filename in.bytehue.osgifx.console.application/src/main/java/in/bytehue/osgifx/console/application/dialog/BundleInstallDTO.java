package in.bytehue.osgifx.console.application.dialog;

import java.io.File;

import org.osgi.dto.DTO;

public final class BundleInstallDTO extends DTO {

    public File    file;
    public boolean startBundle;

    public BundleInstallDTO(final File file, final boolean startBundle) {
        this.file        = file;
        this.startBundle = startBundle;
    }

}