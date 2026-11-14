package com.hippo.ehviewer.subscription;

public final class CheckpointKey {
    public final String accountKey;
    public final String sourceType;
    public final String sourceKey;
    public final String querySignature;

    public CheckpointKey(String accountKey, String sourceType, String sourceKey, String querySignature) {
        this.accountKey = accountKey;
        this.sourceType = sourceType;
        this.sourceKey = sourceKey;
        this.querySignature = querySignature;
    }
}
