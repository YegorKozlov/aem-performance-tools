package com.github.ykozlov.perf.utils;

import org.apache.commons.collections4.bidimap.TreeBidiMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryStringParameters {
    public static void main(String[] args) throws IOException {
        Pattern ptrn = Pattern.compile("(.+) \"(.+)\" (.+) \\[(.+)\\] \"([A-Z]+) (.+) HTTP/1.1\" (\\d+) (.+) \"(.*?)\" \"(.*?)\"");
        Map<String, Integer> counts = new TreeMap<>();
        for(String arg : args) {
            for(String ln : Files.readAllLines(Paths.get(arg))){
                Matcher m = ptrn.matcher(ln);
                if(!m.matches()){
                    continue;
                }
                String path = m.group(6);
                if(path.startsWith("/iojs")){
                    continue;
                }
                int idx = path.indexOf('?');
                if(idx > 0){
                    String[] pairs = path.substring(idx + 1).split("&");
                    for(String pair : pairs){
                        String[] kv = pair.split("=");
                        if(kv.length == 2) {
                            int count = counts.getOrDefault(kv[0], 0);
                            counts.put(kv[0], ++count);
                        }
                    }
                }
            }
        }
        Map.Entry<String, Integer>[] entries = counts.entrySet().toArray(new Map.Entry[0]);
        Arrays.sort(entries, Map.Entry.comparingByValue());
        for(Map.Entry<String, Integer> e : entries){
            System.out.println(e.getKey()+ "\t" + e.getValue());
        }
    }
}
