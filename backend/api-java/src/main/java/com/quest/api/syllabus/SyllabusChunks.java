package com.quest.api.syllabus;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 有界切片；向量首版为单版本精确扫描，绝不是 ANN。 */
final class SyllabusChunks {
    static final int MAX_CHUNKS=5000, MAX_DIMENSIONS=4096;
    record Chunk(int number,int ordinal,String subject,String title,String path,int pageStart,int pageEnd,String printedPages,String text,String hash){}
    static String subject(String exam){return switch(exam){case "career_a"->"vocational_aptitude";case "shanghai_civil"->"administrative_aptitude";default->throw new IllegalArgumentException();};}
    static List<Chunk> split(JsonNode sections,String exam){
        if(!sections.isArray()||sections.size()>1000)throw new IllegalArgumentException();
        var out=new ArrayList<Chunk>();int total=0;
        for(JsonNode section:sections){
            String text=section.path("text").asText();total+=text.length();if(total>2_000_000||text.length()>12000)throw new IllegalArgumentException();
            if(!section.path("enabled").asBoolean()||!section.path("scope").asText().equals(subject(exam))||!Set.of("overview","requirement").contains(section.path("kind").asText()))continue;
            for(int start=0;start<text.length();){
                int end=Math.min(start+1200,text.length());
                // 不把代理对拆成两个非法 Unicode 文本。
                if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
                String part=text.substring(start,end);if(part.isBlank())break;
                out.add(new Chunk(out.size(),section.path("ordinal").asInt(),subject(exam),section.path("title").asText(),section.path("path").asText(),section.path("pageStart").asInt(),section.path("pageEnd").asInt(),section.path("printedPages").asText(),part,hash(part)));
                if(out.size()>MAX_CHUNKS)throw new IllegalArgumentException();if(end==text.length())break;
                start=end-150;if(Character.isLowSurrogate(text.charAt(start)))start++;
            }
        }
        if(out.isEmpty())throw new IllegalArgumentException();return out;
    }
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static double[] vector(JsonNode values,int dimensions){
        if(dimensions<1||dimensions>MAX_DIMENSIONS||!values.isArray()||values.size()!=dimensions)throw new IllegalArgumentException();double[] out=new double[dimensions];double norm=0;
        for(int i=0;i<dimensions;i++){if(!values.get(i).isNumber())throw new IllegalArgumentException();double v=values.get(i).asDouble();if(!Double.isFinite(v)||Math.abs(v)>1e15)throw new IllegalArgumentException();out[i]=v;norm+=v*v;}
        if(!Double.isFinite(norm)||norm==0)throw new IllegalArgumentException();return out;
    }
    static double cosine(double[] a,double[] b){if(a.length!=b.length)throw new IllegalArgumentException();double dot=0,aa=0,bb=0;for(int i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}return dot/Math.sqrt(aa*bb);}
}
