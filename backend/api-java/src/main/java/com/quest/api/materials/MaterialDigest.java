package com.quest.api.materials;

public final class MaterialDigest {
    private MaterialDigest(){}
    public static String of(byte[] input){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException();}}
}
