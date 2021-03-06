package com.firefly.db.jdbc.utils;

import com.firefly.utils.Assert;
import com.firefly.utils.StringUtils;
import com.firefly.utils.io.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Pengtao Qiu
 */
public class MetaDataUtils {

    protected final static Logger log = LoggerFactory.getLogger("firefly-system");

    private static final Map<String, String> defaultJavaTypeMap = new HashMap<>();
    private static final Map<String, String> defaultKotlinTypeMap = new HashMap<>();

    static {
        defaultJavaTypeMap.put("bigint", "Long");
        defaultJavaTypeMap.put("bigint unsigned", "Long");

        defaultJavaTypeMap.put("int", "Integer");
        defaultJavaTypeMap.put("int unsigned", "Integer");
        defaultJavaTypeMap.put("integer", "Integer");
        defaultJavaTypeMap.put("integer unsigned", "Integer");
        defaultJavaTypeMap.put("tinyint", "Integer");
        defaultJavaTypeMap.put("tinyint unsigned", "Integer");

        defaultJavaTypeMap.put("float", "Double");
        defaultJavaTypeMap.put("double", "Double");
        defaultJavaTypeMap.put("decimal", "Double");
        defaultJavaTypeMap.put("real", "Double");

        defaultJavaTypeMap.put("datetime", "java.util.Date");
        defaultJavaTypeMap.put("timestamp", "java.util.Date");
        defaultJavaTypeMap.put("date", "java.util.Date");
        defaultJavaTypeMap.put("time", "java.util.Date");

        defaultKotlinTypeMap.putAll(defaultJavaTypeMap);
        defaultKotlinTypeMap.put("integer", "Int");
        defaultKotlinTypeMap.put("integer unsigned", "Int");
        defaultKotlinTypeMap.put("int", "Int");
        defaultKotlinTypeMap.put("int unsigned", "Int");
        defaultKotlinTypeMap.put("tinyint", "Int");
        defaultKotlinTypeMap.put("tinyint unsigned", "Int");
    }

    protected DataSource dataSource;
    protected String blankString = "    ";
    protected String lineSeparator = "\r\n";
    protected Map<String, String> javaTypeMap = defaultJavaTypeMap;
    protected Map<String, String> kotlinTypeMap = defaultKotlinTypeMap;

    public MetaDataUtils() {
    }

    public MetaDataUtils(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getBlankString() {
        return blankString;
    }

    public void setBlankString(String blankString) {
        this.blankString = blankString;
    }

    public String getLineSeparator() {
        return lineSeparator;
    }

    public void setLineSeparator(String lineSeparator) {
        this.lineSeparator = lineSeparator;
    }

    public Map<String, String> getJavaTypeMap() {
        return javaTypeMap;
    }

    public void setJavaTypeMap(Map<String, String> javaTypeMap) {
        this.javaTypeMap = javaTypeMap;
    }

    public Map<String, String> getKotlinTypeMap() {
        return kotlinTypeMap;
    }

    public void setKotlinTypeMap(Map<String, String> kotlinTypeMap) {
        this.kotlinTypeMap = kotlinTypeMap;
    }

    public List<TableMetaData> listTableMetaData(String catalog, String schemaPattern, String tableNamePattern) {
        List<TableMetaData> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData dbMetaData = conn.getMetaData();
            ResultSet resultSet = dbMetaData.getTables(catalog, schemaPattern, tableNamePattern, new String[]{"TABLE"});

            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                TableMetaData tableMetaData = new TableMetaData();
                tableMetaData.setCatalog(catalog);
                tableMetaData.setName(tableName.toLowerCase());
                tableMetaData.setColumnMetaDataList(new ArrayList<>());

                ResultSet pkResultSet = dbMetaData.getPrimaryKeys(catalog, null, tableName);
                while (pkResultSet.next()) {
                    tableMetaData.setPkColumnName(pkResultSet.getString("COLUMN_NAME"));
                }

                ResultSet colResultSet = dbMetaData.getColumns(catalog, schemaPattern, tableName, "%");
                while (colResultSet.next()) {
                    String colName = colResultSet.getString("COLUMN_NAME");
                    String colType = colResultSet.getString("TYPE_NAME");
                    ColumnMetaData columnMetaData = new ColumnMetaData();
                    columnMetaData.setName(colName.toLowerCase());
                    columnMetaData.setType(colType.toLowerCase());
                    tableMetaData.getColumnMetaDataList().add(columnMetaData);
                }
                list.add(tableMetaData);
            }
        } catch (SQLException e) {
            log.error("get metadata exception", e);
        }
        return list;
    }

    public List<SourceCode> toJavaDataClass(List<TableMetaData> list, String tablePrefix, String packageName) {
        return list.parallelStream().filter(m -> m.getName().startsWith(tablePrefix)).map(m -> {
            SourceCode code = new SourceCode();
            code.setName(toClassName(tablePrefix, m.getName()));

            StringBuilder codes = new StringBuilder();
            codes.append("package ").append(packageName).append(";").append(lineSeparator)
                 .append(lineSeparator)
                 .append("import lombok.Data;").append(lineSeparator)
                 .append(lineSeparator)
                 .append("import java.io.Serializable;").append(lineSeparator)
                 .append(lineSeparator)
                 .append("@Data").append(lineSeparator)
                 .append("public class ").append(code.getName()).append(" implements Serializable {").append(lineSeparator)
                 .append(lineSeparator)
                 .append(blankString).append("private static final long serialVersionUID = 1L;").append(lineSeparator)
                 .append(lineSeparator);

            m.getColumnMetaDataList()
             .forEach(c -> codes.append(blankString).append("private ")
                                .append(toJavaPropertyType(c.getType())).append(" ")
                                .append(toPropertyName(c.getName())).append(";")
                                .append(lineSeparator));
            codes.append("}");
            code.setCodes(codes.toString());
            return code;
        }).collect(Collectors.toList());
    }

    public List<SourceCode> toKotlinDataClass(List<TableMetaData> list, String tablePrefix, String packageName) {
        return list.parallelStream().filter(m -> m.getName().startsWith(tablePrefix)).map(m -> {
            SourceCode code = new SourceCode();
            code.setName(toClassName(tablePrefix, m.getName()));

            StringBuilder codes = new StringBuilder();
            codes.append("package ").append(packageName).append(lineSeparator)
                 .append(lineSeparator)
                 .append("import com.firefly.db.annotation.*").append(lineSeparator)
                 .append("import java.io.Serializable").append(lineSeparator)
                 .append(lineSeparator)
                 .append("@Table(value = ").append('"').append(m.getName()).append('"')
                 .append(", catalog = \"").append(m.getCatalog()).append("\")").append(lineSeparator)
                 .append("data class ").append(code.getName()).append('(').append(lineSeparator);

            m.getColumnMetaDataList().forEach(c -> {
                codes.append(blankString);
                if (c.getName().equals(m.getPkColumnName())) {
                    codes.append("@Id");
                } else {
                    codes.append("@Column");
                }
                codes.append("(\"").append(c.getName()).append("\") var ")
                     .append(toPropertyName(c.getName())).append(": ")
                     .append(toKotlinPropertyType(c.getType())).append("?, ").append(lineSeparator);
            });
            codes.delete(codes.length() - lineSeparator.length() - 2, codes.length());
            codes.append(") : Serializable {").append(lineSeparator)
                 .append(blankString).append("companion object {").append(lineSeparator)
                 .append(blankString).append(blankString).append("private const val serialVersionUID: Long = 1").append(lineSeparator)
                 .append(blankString).append("}").append(lineSeparator)
                 .append("}");

            code.setCodes(codes.toString());
            return code;
        }).collect(Collectors.toList());
    }

    public void generateJavaDataClass(String catalog, String schemaPattern, String tableNamePattern,
                                      String tablePrefix, String packageName,
                                      Path path) {
        List<TableMetaData> list = listTableMetaData(catalog, schemaPattern, tableNamePattern);
        List<SourceCode> sourceCodes = toJavaDataClass(list, tablePrefix, packageName);
        write(path, sourceCodes, "java");
    }

    public void generateKotlinDataClass(String catalog, String schemaPattern, String tableNamePattern,
                                        String tablePrefix, String packageName,
                                        Path path) {
        List<TableMetaData> list = listTableMetaData(catalog, schemaPattern, tableNamePattern);
        List<SourceCode> sourceCodes = toKotlinDataClass(list, tablePrefix, packageName);
        write(path, sourceCodes, "kt");
    }

    private void write(Path path, List<SourceCode> sourceCodes, String fileSuffix) {
        Assert.isTrue(Files.isDirectory(path), "The path must be a directory");
        sourceCodes.forEach(s -> {
            Path sourcePath = Paths.get(path.toAbsolutePath().toString(), s.getName() + "." + fileSuffix);
            try (FileChannel channel = FileChannel.open(sourcePath, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE)) {
                channel.write(BufferUtils.toBuffer(s.getCodes()));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public String toClassName(String tablePrefix, String tableName) {
        String[] tableNameArr = StringUtils.hasText(tablePrefix)
                ? StringUtils.split(tableName.substring(tablePrefix.length()), '_')
                : StringUtils.split(tableName, '_');
        return Arrays.stream(tableNameArr)
                     .map(s -> Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : ""))
                     .collect(Collectors.joining());
    }

    public String toJavaPropertyType(String columnType) {
        String r = javaTypeMap.get(columnType);
        if (StringUtils.hasText(r)) {
            return r;
        } else {
            return "String";
        }
    }

    public String toKotlinPropertyType(String columnType) {
        String r = kotlinTypeMap.get(columnType);
        if (StringUtils.hasText(r)) {
            return r;
        } else {
            return "String";
        }
    }

    public String toPropertyName(String columnName) {
        String[] colNameArr = StringUtils.split(columnName, '_');
        String p = Arrays.stream(colNameArr)
                         .map(s -> Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : ""))
                         .collect(Collectors.joining());
        return Character.toLowerCase(p.charAt(0)) + (p.length() > 1 ? p.substring(1) : "");
    }

}
