package io.velora.host;

public interface FileTransaction {

    String scriptId();

    FileTransaction write(String relativePath, String content, FileRevision expectedRevision);

    FileTransaction delete(String relativePath);

    FileTransaction validateExpectedRevision(String relativePath, FileRevision expected);

    boolean commit();

    void rollback();

    boolean isCommitted();
}
