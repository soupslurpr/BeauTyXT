// IFileViewModelRustLibraryAidlInterface.aidl
package dev.soupslurpr.beautyxt;

// Declare any non-default types here with import statements

interface IFileViewModelRustLibraryAidlInterface {
    String markdownToHtml(String markdown);
    byte[] markdownToDocx(String markdown);
    byte[] plainTextToDocx(String plainText);
}
