// ITypstProjectViewModelRustLibraryAidlInterface.aidl
package dev.soupslurpr.beautyxt;

// Declare any non-default types here with import statements
import dev.soupslurpr.beautyxt.PathAndPfd;

interface ITypstProjectViewModelRustLibraryAidlInterface {
    void initializeTypstWorld();
    void setMainTypstProjectFile(in PathAndPfd papfd);
    void addTypstProjectFiles(in List<PathAndPfd> papfdList);
    String getTypstProjectFileText(String path);
    Bundle getTypstSvg();
    byte[] getTypstPdf();
    String updateTypstProjectFile(String newText, String path);
    void clearTypstProjectFiles();
}