package com.github.ykozlov.perf.utils;


import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * @author Yegor Kozlov
 */
public class Report implements Iterable<Report.Row> {
    private static final Logger logger = LoggerFactory.getLogger(Report.class);

    public static final String STYLE_GOOD = "good";
    public static final String STYLE_BAD = "bad";
    public static final String STYLE_HEADER = "header";
    public static final String STYLE_NEUTRAL = "neutral";
    public static final String STYLE_LIGHT_BLUE = "lightblue";
    public static final String STYLE_DARK_BLUE = "darkblue";
    public static final String STYLE_F2 = "f2";
    public static final String STYLE_F0 = "f0";
    public static final String STYLE_DATE = "date";
    public static final String STYLE_DATETIME = "datetime";
    public static final String STYLE_HYPERLINK = "hyperlink";

    private static Pattern NUMBER = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+");

    public static enum Format {
        xlsx,
        txt
    }

    public class Row {
        private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        private TreeMap<Integer, String> values;
        private Map<Integer, String> cellStyles = new HashMap<>();
        private String rowStyle;
        private String rowId;
        private int index;

        public Row(){
            values = new TreeMap<>();
        }

        public void setId(String id){
            rowId = id;
        }

        public String getId(){
            return rowId;
        }
        public int getIndex() {
            return index;
        }

        @Override
        public String toString(){
            StringBuilder buf = new StringBuilder();
            int numColumns = values.isEmpty() ? 0 : values.lastKey();
            for(int i=0; i <=  numColumns; i++){
                if(i > 0) buf.append('\t');
                String val = values.get(i);
                buf.append(val == null ? "-" : val.replace('\t', ' '));
            }
            return buf.toString();
        }

        public Row setValue(int columnIndex, String val){
            values.put(columnIndex, val);
            if(rowMaps != null) {
                Map<String, Row> rowMap = rowMaps.get(columnIndex);
                if (rowMap != null) rowMap.put(val, this);
            }
            isModified = true;
            return this;
        }

        public Row setValue(char columnIndex, String val){
            return setValue(columnIndex - 'A', val);
        }

        public Row setValue(int columnIndex, Number val){
            return setValue(columnIndex, val.toString());
        }

        public void setCellStyle(int columnIndex, String style){
            cellStyles.put(columnIndex, style);
        }

        public void setCellStyle(char columnIndex, String style){
            cellStyles.put(columnIndex - 'A', style);
        }

        public String getCellStyle(int columnIndex){
            return cellStyles.get(columnIndex);
        }

        public void setValue(int columnIndex, Date val){
            values.put(columnIndex, val == null ? null : ("{DateTime}" + dateFormat.format(val)));
        }

        public String getValue(int columnIndex){
            return values.get(columnIndex);
        }

        public String getValue(char column){
            return values.get(column - 'A');
        }

        public void setRowStyle(String styleName){
            rowStyle = styleName;
        }

        public String getRowStyle(){
            return rowStyle;
        }

        public Map<Integer, String> getValues(){
            return values;
        }
    }

    private List<Row> rows = Collections.synchronizedList(new ArrayList<Row>());
    private Map<Integer, Map<String, Row>> rowMaps = Collections.synchronizedMap(new HashMap<Integer, Map<String, Row>>());
    private String[] columns = {};
    Map<String, CellStyle> styleMap = new HashMap<>();
    boolean isModified = false;
    int groupColumnIndex = -1;
    int autoresize = 0;
    boolean freezeTopRow = false;
    String sheetName = null;
    int[] hiddenColumns = null;
    Map<Integer, Integer> columnWidths = new LinkedHashMap<>();

    private int MAX_STRING_LENGTH = 32767;

    public Report(){

    }

    public Report(File file) throws IOException {
        load(file);
    }

    public Report(InputStream  is) throws IOException {
        loadXls(is, null);
    }

    public Report(Reader is) throws IOException {
        loadTxt(new BufferedReader(is));
    }

    public void clear(){
        rows.clear();
        rowMaps.clear();
    }

    public Iterator<Row> iterator(){
        return rows.iterator();
    }

    public Row createRow(){
        Row row = new Row();
        add(row);
        return row;
    }

    @SuppressWarnings("unchecked")
    public Row createRow(Row src){
        Row row = createRow();
        row.values = (TreeMap<Integer, String>)src.values.clone();
        row.cellStyles = src.cellStyles;
        row.rowStyle = src.rowStyle;

        return row;
    }

    public Row getRow(int idx){
        return rows.get(idx);
    }

    public Row lastRow(){
        return rows.get(rows.size() - 1);
    }

    public synchronized void add(Row row){
        row.index = rows.size();
        rows.add(row);
    }

    public void setColumns(String[] cols){
        columns = cols;
    }

    public void setColumnWidth(int idx, int width){
        columnWidths.put(idx, width);
    }

    public int addColumn(String col){
        columns = Arrays.copyOf(columns, columns.length + 1);//columns = cols;
        int columnIndex = columns.length - 1;
        columns[columnIndex] = col;
        return columnIndex;
    }

    public String[] getColumns(){
        return columns;
    }

    public Row lookup(int columnIndex, String key){
        return lookup(columnIndex, key, true);
    }

    public boolean deleteRow(Row row){
        return rows.remove(row);
    }

    public Row lookup(int columnIndex, String key, boolean caseSensitive){
        if(rowMaps == null) rowMaps = Collections.synchronizedMap(new HashMap<Integer, Map<String, Row>>());

        int cacheIndex = caseSensitive ? columnIndex : -columnIndex;
        Map<String, Row> rowMap = rowMaps.get(cacheIndex);
        if(rowMap == null){
            rowMap = new HashMap<>(rows.size());
            for(Row e : rows) {
                String v = e.getValue(columnIndex);
                if(!caseSensitive && v != null) v = v.toLowerCase();
                if(!rowMap.containsKey(v)) rowMap.put(v, e);
            }
            rowMaps.put(cacheIndex, rowMap);
        }
        return rowMap.get(caseSensitive ? key : key.toLowerCase());
    }

    public boolean matchPath (int columnIndex, String key) {
        for(Row e : rows) {
            String v = e.getValue(columnIndex);
            if (v.contains(key)) return true;
        }
        return false;
    }

    public Row getById(String key){
        Map<String, Row> rowMap = rowMaps.get(-1);
        if(rowMap == null){
            rowMap = new HashMap<>(rows.size());
            for(Row e : rows) {
                String v = e.getId();
                if(!rowMap.containsKey(v)) rowMap.put(v, e);
            }
            rowMaps.put(-1, rowMap);
        }
        return rowMap.get(key);
    }

    public void setSheetName(String sheetName){
        this.sheetName = sheetName;
    }
    public void hideColumns(int ... columns){
        this.hiddenColumns = columns;
    }

    public void save(String fileName) throws IOException {
        File f = new File(fileName);

        if(f.getParentFile() != null) f.getParentFile().mkdirs();
        // files checked out from TFS are readonly. ensure user can write in ./etc
        if(f.getParentFile() != null && !f.getParentFile().canWrite()) throw new Error("Cannot write to " + f.getParentFile() + ". Aborting .... ");

        if(fileName.endsWith(".xlsx")){
            saveXls(fileName);
        } else {
            try (Writer fw = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")){
                write(fw);
            }
        }
        isModified = false;
    }

    public boolean isModified(){
        return isModified;
    }

    public void groupRows(int groupColumnIndex){
        this.groupColumnIndex = groupColumnIndex;
    }

    public void autoresize(int rows){
        autoresize = rows;
    }

    private CellStyle getStyle(Workbook wb, String styleName){
        CellStyle style = styleMap.get(styleName);
        if(style == null){
            style = wb.createCellStyle();
            setStyleAttributes(styleName, style, wb);
            styleMap.put(styleName, style);
        }
        return style;
    }

    private void saveXls(String fileName) throws IOException {
        SXSSFWorkbook wb = new SXSSFWorkbook();
        SXSSFSheet sheet = wb.createSheet();
        sheet.trackAllColumnsForAutoSizing();
        if(sheetName != null) wb.setSheetName(0, sheetName);
        save(sheet);
        if(hiddenColumns != null) {
            for(int i : hiddenColumns){
                sheet.setColumnHidden(i, true);
            }
        }
        for(Map.Entry<Integer, Integer> e : columnWidths.entrySet()){
            int columnIndex = e.getKey();
            int width = e.getValue();
            sheet.setColumnWidth(columnIndex, width);
        }

        logger.info("saving report as " + fileName);
        try (FileOutputStream out = new FileOutputStream(fileName)){
            wb.write(out);
        }

        wb.dispose();
        styleMap.clear();
    }

    public void freezeTopRow(){
        freezeTopRow = true;
    }

    public void disableRowMaps(){
        rowMaps = null;
    }

    public void save(Sheet sheet) throws IOException {
        sheet.setRowSumsBelow(false);
        Workbook wb = sheet.getWorkbook();
        if(freezeTopRow) sheet.createFreezePane(0, 1, 0, 1);
        int rowNumber = 0;
        org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(rowNumber++);
        for(int i = 0; i < columns.length; i++){
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns[i]);
            String styleName = "header";
            CellStyle style = getStyle(wb, styleName);
            cell.setCellStyle(style);
        }

        int lastColumnIndex = 0;
        int lastReportRow = -1;
        String groupValue = null;
        for(Row r : rows){
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowNumber++);

            if(r.values.isEmpty()) continue;

            CellStyle rowStyle = null;
            String styleName = r.getRowStyle();
            if(styleName != null){
                rowStyle = getStyle(wb, styleName);
            }
            int columnIndex;
            for(columnIndex = 0; columnIndex < Math.max(r.values.lastKey() + 1, columns.length); columnIndex++){
//                if(row.getRowNum() % 100 == 0) System.out.println(row.getRowNum() +
//                        " (" + String.format("%.2f", 100.*row.getRowNum()/rows.size()) + "%)");
                Cell cell = row.createCell(columnIndex);
                String value = r.values.get(columnIndex);
                if(groupColumnIndex != -1 && columnIndex == 0 && value != null) {
                    groupValue = value;
                    if(lastReportRow != -1 && (row.getRowNum() - lastReportRow) > 1){
                        sheet.groupRow(lastReportRow+1, row.getRowNum()-1);
                    }
                    lastReportRow = row.getRowNum();
                }

                if(value != null) {
                    if(NUMBER.matcher(value).matches() && value.length() < 15) {
                        try {
                            cell.setCellValue(Double.parseDouble(value));
                        } catch (Exception e){
                            logger.warn(e.getMessage());
                            cell.setCellValue(value);
                        }
                    }
                    else if (value.startsWith("=")) {
                        cell.setCellFormula(value.substring(1));
                    }
                    else if (value.toLowerCase().matches("true|false")) {
                        cell.setCellValue(Boolean.valueOf(value));
                    }
                    else if (value.startsWith("{Date}")) {
                        try {
                            Date date = FastDateFormat.getInstance("yyyy-MM-dd").parse(value.substring(6));
                            cell.setCellValue(date);
                            cell.setCellStyle(getStyle(wb, STYLE_DATE));
                        } catch (ParseException e){
                            cell.setCellValue(value.substring(6));
                        }
                    }
                    else if (value.startsWith("{DateTime}")) {
                        try {
                            Date date = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss").parse(value.substring(10));
                            cell.setCellValue(date);
                            cell.setCellStyle(getStyle(wb, STYLE_DATETIME));
                        } catch (ParseException e){
                            cell.setCellValue(value.substring(6));
                        }
                    }
                    else {
                        if(value.length() > MAX_STRING_LENGTH ) value = value.substring(0, MAX_STRING_LENGTH);
                        cell.setCellValue(value);
                    }

                } else if (groupValue != null && columnIndex == 0){
                    //cell.setCellValue(groupValue);
                }
                lastColumnIndex = Math.max(lastColumnIndex, columnIndex);

                CellStyle style = null;

                styleName = r.getCellStyle(columnIndex);
                if(styleName != null) style = getStyle(wb, styleName);
                if(style == null) style = rowStyle;

                if(style != null) cell.setCellStyle(style);
            }
            if(autoresize > 0 && (rowNumber % autoresize) == 0) autosize(sheet, lastColumnIndex);
        }
        if(groupColumnIndex != -1 && lastReportRow != -1 && (sheet.getLastRowNum() - lastReportRow) > 1){
            sheet.groupRow(lastReportRow+1, sheet.getLastRowNum());
        }
        autosize(sheet, lastColumnIndex);
        sheet.setAutoFilter(new CellRangeAddress(0, rowNumber-1, 0, lastColumnIndex));
    }

    void autosize(Sheet sheet, int lastColumnIndex){
        for(int i = 0; i <= lastColumnIndex; i++ ) {
            if(columnWidths.containsKey(i)) {
                continue;
            } else {
                try {
                    sheet.autoSizeColumn(i);
                } catch (Exception e) {
                    logger.warn("autoSizeColumn(" + i + ") failed: " + e.getMessage());
                }
                int cw = sheet.getColumnWidth(i);
                // increase width to accommodate drop-down arrow in the header
                if (cw / 256 < 20) sheet.setColumnWidth(i, 256 * 12);
                else if (cw / 256 > 120) sheet.setColumnWidth(i, 256 * 120);
            }
        }
    }

    public void load(File file) throws IOException {
        String fileName = file.getName();
        if(fileName.endsWith(".xlsx")) loadXls(file, null);
        else if (fileName.endsWith(".csv")) loadCsv(file);
        else loadTxt(file);
    }

    /**
     * load from a tab-delimited file
     */
    private void loadTxt(File file) throws IOException {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))){
            loadTxt(reader);
        }
    }

    private void loadTxt(BufferedReader reader) throws IOException {
        clear();

        String line;// = reader.readLine();
        //if(line != null) columns = line.split("\t");
        while((line = reader.readLine()) != null){
            String[] vals = line.split("\t");

            Row e = new Row();
            for(int i = 0; i < vals.length; i++){
                String v = "-".equals(vals[i]) ? null : vals[i];
                e.values.put(i, v);
            }

            add(e);
        }
    }

    /**
     * load from a .xlsx file
     */
    public void loadXls(File file, String sheetName) throws IOException {
        try(FileInputStream is = new FileInputStream(file)){
            loadXls(is, sheetName);
        }
    }

    public void loadXls(InputStream is, String sheetName) throws IOException {
        Workbook wb = new XSSFWorkbook(is);
        loadXls(wb, sheetName);
    }

    public void loadXls(Workbook wb, String sheetName) throws IOException {
        clear();

        Sheet sheet = sheetName == null ? wb.getSheetAt(0) : wb.getSheet(sheetName);
        if(sheet == null) throw new IllegalArgumentException("Invalid sheet name: " + sheetName);
        DataFormatter df = new DataFormatter();
        boolean first = true;
        for(org.apache.poi.ss.usermodel.Row r : sheet){
            if(first){
                columns = new String[r.getLastCellNum() == -1 ? 0 : r.getLastCellNum()];
                for(Cell c : r) columns[c.getColumnIndex()] = df.formatCellValue(c);

            } else {
                Row e = new Row();
                for(Cell c : r) {
                    if(c.getCellType() != CellType.ERROR) {
                        e.values.put(c.getColumnIndex(), df.formatCellValue(c));
                    }
                }
                add(e);

            }
            first = false;
        }
    }

    public static Map<String, Report> loadXls(File file) throws IOException {
        try(FileInputStream is = new FileInputStream(file)){
            return loadXls(is);
        }
    }
    public static Map<String, Report> loadXls(InputStream is) throws IOException {

        Workbook wb = new XSSFWorkbook(is);
        Map<String,Report> map = new LinkedHashMap<String,Report>();
        int numSheets = wb.getNumberOfSheets();
        for(int i=0;i < numSheets; ++i) {
            Sheet sheet = wb.getSheetAt(i);
            if(sheet == null) throw new IndexOutOfBoundsException("Invalid sheet index: " + i);

            Report re= new Report();
            DataFormatter df = new DataFormatter();
            boolean first = true;
            for(org.apache.poi.ss.usermodel.Row r : sheet){
                if(first){
                    String[] columns = new String[r.getLastCellNum() == -1 ? 0 : r.getLastCellNum()];
                    for(Cell c : r) columns[c.getColumnIndex()] = df.formatCellValue(c);
                    re.setColumns(columns);

                } else {
                    Row e = re.new Row();
                    for(Cell c : r) {
                        e.values.put(c.getColumnIndex(), df.formatCellValue(c));
                    }
                    re.add(e);
                }
                first = false;
            }
            map.put(sheet.getSheetName(), re);
        }
        return map;
    }

    /**
     * load from .csv file
     */
    private void loadCsv(File file) throws IOException {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))){
            loadCsv(reader);
        }
    }

    private void loadCsv(BufferedReader reader) throws IOException {
        clear();

        String line = reader.readLine();
        if(line != null) columns = line.split(",");
        while((line = reader.readLine()) != null){
            String[] vals = line.split(",");

            Row e = new Row();
            for(int i = 0; i < vals.length; i++){
                String v = "".equals(vals[i]) ? null : vals[i];
                e.values.put(i, v);
            }

            add(e);
        }
    }

    public void write(Writer out) throws IOException {
        if(columns != null) {
            for(int i = 0; i < columns.length; i++){
                if(i > 0) out.write('\t');
                String fld = columns[i];
                int idx = fld.indexOf(':');
                if(idx > 0) fld = fld.substring(0, idx);
                out.write(fld);
            }
            out.write('\n');
        }

        for(Row e : rows){
            out.write(e.toString().replaceAll("[\r\n]", " "));
            out.write('\n');
        }
    }

    public int size(){
        return rows.size();
    }

    public static void main(String[] args) throws IOException{

        Report r = new Report();
        Row e = r.createRow();
        e.setValue(0, "0");
        e.setValue(3, "3");
        r.add(e);
        e = r.createRow();
        e.setValue(1, "1");
        e.setValue(2, "2");
        r.add(e);

        r.load(new File("etc/le-assets.txt"));
        r.save("text.xlsx");
    }

    private void setStyleAttributes(String styleName, CellStyle style, Workbook wb){
        XSSFCellStyle xstyle = (XSSFCellStyle)style;
        switch (styleName){
            case STYLE_GOOD:
                XSSFColor green = new XSSFColor(
                        new byte[]{(byte)198, (byte)239, (byte)206}, new DefaultIndexedColorMap()
                );
                xstyle.setFillForegroundColor(green);
                xstyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f1 = (XSSFFont)wb.createFont();
                f1.setColor(new XSSFColor(
                        new byte[]{(byte)0, (byte)97, (byte)0}, new DefaultIndexedColorMap()
                ));
                xstyle.setFont(f1);
                break;
            case STYLE_BAD:
                XSSFColor red = new XSSFColor(new byte[]{(byte)255, (byte)199, (byte)206}, new DefaultIndexedColorMap() );
                xstyle.setFillForegroundColor(red);
                xstyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f2 = (XSSFFont)wb.createFont();
                f2.setColor(new XSSFColor(
                        new byte[]{(byte)156, (byte)0, (byte)6}, new DefaultIndexedColorMap()
                ));
                xstyle.setFont(f2);
                break;
            case STYLE_HEADER:
                XSSFColor header = new XSSFColor(new byte[]{(byte)79, (byte)129, (byte)189}, new DefaultIndexedColorMap() );
                xstyle.setFillForegroundColor(header);
                xstyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f3 = (XSSFFont)wb.createFont();
                f3.setColor(IndexedColors.WHITE.index);
                xstyle.setFont(f3);
                break;
            case STYLE_NEUTRAL:
                XSSFColor yellow = new XSSFColor(new byte[]{(byte)255, (byte)235, (byte)156}, new DefaultIndexedColorMap() );
                xstyle.setFillForegroundColor(yellow);
                xstyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f4 = (XSSFFont)wb.createFont();
                f4.setColor(new XSSFColor(
                        new byte[]{(byte)156, (byte)101, (byte)0}, new DefaultIndexedColorMap()
                ));
                xstyle.setFont(f4);
                break;
            case STYLE_LIGHT_BLUE:
                XSSFColor lightBlue = new XSSFColor(new byte[]{(byte)75, (byte)172, (byte)198}, new DefaultIndexedColorMap() );
                xstyle.setFillForegroundColor(lightBlue);
                xstyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f5 = (XSSFFont)wb.createFont();
                f5.setColor(new XSSFColor(
                        new byte[]{(byte)255, (byte)255, (byte)255}, new DefaultIndexedColorMap()
                ));
                xstyle.setFont(f5);
                break;
            case STYLE_DARK_BLUE:
                XSSFColor darkBlue = new XSSFColor(new byte[]{(byte)79, (byte)129, (byte)189}, new DefaultIndexedColorMap() );
                xstyle.setFillForegroundColor(darkBlue);
                xstyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f6 = (XSSFFont)wb.createFont();
                f6.setColor(new XSSFColor(
                        new byte[]{(byte)255, (byte)255, (byte)255}, new DefaultIndexedColorMap()
                ));
                xstyle.setFont(f6);
                break;
            case STYLE_F2:
                xstyle.setDataFormat((short)2);
                break;
            case STYLE_F0:
                xstyle.setDataFormat((short)1);
                break;
            case STYLE_DATE:
                xstyle.setDataFormat(
                        wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
                break;
            case STYLE_DATETIME:
                xstyle.setDataFormat(
                        wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
                break;
            case STYLE_HYPERLINK:
                XSSFFont f7 = (XSSFFont)wb.createFont();
                f7.setColor(new XSSFColor(
                        new byte[]{(byte)0, (byte)0, (byte)255}, new DefaultIndexedColorMap()
                ));
                f7.setUnderline(FontUnderline.SINGLE);
                xstyle.setFont(f7);
                break;
        }
    }
}
