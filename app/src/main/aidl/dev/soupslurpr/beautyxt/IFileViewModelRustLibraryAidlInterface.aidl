// IFileViewModelRustLibraryAidlInterface.aidl
package dev.soupslurpr.beautyxt;

// Declare any non-default types here with import statements

interface IFileViewModelRustLibraryAidlInterface {
    void apply_seccomp_bpf();
    String markdownToHtml(String markdown);
    byte[] markdownToDocx(String markdown);
    byte[] plainTextToDocx(String plainText);
}
