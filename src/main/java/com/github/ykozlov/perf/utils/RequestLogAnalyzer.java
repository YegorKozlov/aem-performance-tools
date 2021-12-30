package com.github.ykozlov.perf.utils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestLogAnalyzer {
    static final Pattern REQUEST_STARTED = Pattern.compile("(.{26}) \\[(\\d+)\\] -> (\\w+) (.+) HTTP/1.1");
    static final Pattern REQUEST_ENDED = Pattern.compile("(.{26}) \\[(\\d+)\\] <- (\\d+) (.+) (\\d+)ms");
    private static SimpleDateFormat df = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);

    private static void usage() {

    }

    public static void main(String[] args) throws IOException, ParseException {
        List<String> paths = new ArrayList<>();
        List<String> methods = Arrays.asList("GET");
        List<Pattern> skipPatterns = new ArrayList<>();
        String saveAs = "requests.xlsx";
        int rowLimit = Integer.MAX_VALUE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--skip":
                    skipPatterns.add(Pattern.compile(args[++i]));
                    break;
                case "--save":
                    saveAs = args[++i];
                    break;
                case "--rows":
                    rowLimit = Integer.parseInt(args[++i]);
                    break;
                case "--method":
                    methods.add(args[++i]);
                    break;
                default:
                    paths.add(args[i]);
                    break;
            }
        }
        if (paths.isEmpty()) {
            usage();
            return;
        }

        Map<String, Matcher> r1 = new LinkedHashMap<>();
        for (String path : paths) {
            List<Request> requests = new ArrayList<>();
            for (String line : Files.readAllLines(Paths.get(path))) {
                Matcher m1 = REQUEST_STARTED.matcher(line);
                if (m1.matches()) {
                    String id = m1.group(2);
                    r1.put(id, m1);
                } else {
                    Matcher mEnd = REQUEST_ENDED.matcher(line);
                    if (mEnd.matches()) {
                        String id = mEnd.group(2);
                        Matcher mStart = r1.get(id);
                        if (mStart != null) {
                            Request r = new Request(mStart, mEnd);
                            r1.remove(id);
                            if (!methods.contains(r.method) || skipPatterns.stream().anyMatch(p -> p.matcher(r.path).matches())) {
                                continue;
                            }
                            requests.add(r);
                        } else {
                            System.err.println("unmatched request: " + id);
                        }
                    } else {
                        System.err.println("invalid request line: " + line);
                    }
                }
            }
            System.out.println(requests.size() + " requests loaded");
            try (FileOutputStream out = new FileOutputStream(saveAs)) {
                System.out.println("saving as " + saveAs);

                SXSSFWorkbook wb = new SXSSFWorkbook();
                CellStyle dateStyle = wb.createCellStyle();
                short fmt = wb.createDataFormat().getFormat("m/d/yyyy h:mm:ss");
                dateStyle.setDataFormat(fmt);
                SXSSFSheet sheet = wb.createSheet();
                //sheet.trackAllColumnsForAutoSizing();
                int rownum = 0;
                Row row = sheet.createRow(rownum++);
                row.createCell(0).setCellValue("time started");
                row.createCell(1).setCellValue("elapsed");
                row.createCell(2).setCellValue("method");
                row.createCell(3).setCellValue("path");
                for (Request r : requests) {
                    if (rownum > rowLimit) {
                        break;
                    }
                    row = sheet.createRow(rownum++);
                    Date timestamp = df.parse(r.timeStarted);
                    Cell cellA = row.createCell(0);
                    cellA.setCellValue(timestamp);
                    cellA.setCellStyle(dateStyle);
                    row.createCell(1).setCellValue(r.elapsed);
                    row.createCell(2).setCellValue(r.method);
                    row.createCell(3).setCellValue(r.path);
                }
                System.out.println(rownum + " rows written");
                for (int i = 0; i < 4; i++) {
                    //sheet.autoSizeColumn(i);
                }
                sheet.setAutoFilter(new CellRangeAddress(0, rownum, 0, 3));
                wb.write(out);
                wb.close();
            }
        }
    }

    static class Request {
        Request(Matcher started, Matcher ended) {
            timeStarted = started.group(1);
            id = started.group(2);
            method = started.group(3);
            path = started.group(4);
            timeEnded = ended.group(1);
            String id2 = ended.group(2);
            if (!id.equals(id2)) {
                throw new IllegalArgumentException("start and end requests have different ids: " + id2 + ", " + id2);
            }
            status = Long.parseLong(ended.group(3));
            contentType = ended.group(4);
            elapsed = Long.parseLong(ended.group(5));
        }

        final String timeStarted;
        final String timeEnded;
        final String id;
        final long elapsed;
        final String method;
        final String path;
        final String contentType;
        final long status;

        @Override
        public String toString() {
            return "Request{" +
                    "timeStarted='" + timeStarted + '\'' +
                    ", status='" + status + '\'' +
                    ", id='" + id + '\'' +
                    ", elapsed=" + elapsed +
                    ", path='" + path + '\'' +
                    ", method='" + method + '\'' +
                    ", contentType='" + contentType + '\'' +
                    ", timeEnded='" + timeEnded + '\'' +
                    '}';
        }
    }
}
