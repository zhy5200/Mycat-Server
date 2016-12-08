package io.mycat.migrate;

import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.fastjson.JSON;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import io.mycat.MycatServer;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.backend.datasource.PhysicalDBPool;
import io.mycat.backend.datasource.PhysicalDatasource;
import io.mycat.config.model.DBHostConfig;
import io.mycat.config.model.SystemConfig;
import io.mycat.route.function.PartitionByCRC32PreSlot.Range;
import io.mycat.util.ProcessUtil;
import io.mycat.util.ZKUtils;
import io.mycat.util.dataMigrator.DataMigratorUtil;
import io.mycat.util.dataMigrator.DataNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static io.mycat.util.dataMigrator.DataMigratorUtil.executeQuery;


/**
 * Created by nange on 2016/12/1.
 */
public class MigrateDumpRunner implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrateDumpRunner.class);
    private MigrateTask task;
    private CountDownLatch latch;
    private AtomicInteger sucessTask;
    public MigrateDumpRunner(MigrateTask task, CountDownLatch latch, AtomicInteger sucessTask) {
        this.task = task;
        this.latch = latch;
        this.sucessTask=sucessTask;
    }

    @Override public void run() {
        try {
        String mysqldump = "?mysqldump -h? -P? -u? -p?  ? ? --single-transaction -q --default-character-set=utf8mb4 --hex-blob --where=\"?\" --master-data=1  -T  \"?\"  --fields-enclosed-by=\\\" --fields-terminated-by=, --lines-terminated-by=\\n  --fields-escaped-by=\\\\ ";
        PhysicalDBPool dbPool = MycatServer.getInstance().getConfig().getDataNodes().get(task.getFrom()).getDbPool();
        PhysicalDatasource datasource = dbPool.getSources()[dbPool.getActivedIndex()];
        DBHostConfig config = datasource.getConfig();
        File file = null;
       String spath=   querySecurePath(config);
        if(Strings.isNullOrEmpty(spath)||"NULL".equalsIgnoreCase(spath)||"empty".equalsIgnoreCase(spath)) {
            file = new File(SystemConfig.getHomePath() + File.separator + "temp",
                    task.getFrom() + "_" + task.getTo() + Thread.currentThread().getId() + System.currentTimeMillis() + "");
        }   else {
            spath+= Thread.currentThread().getId() + System.currentTimeMillis();
            file=new File(spath);
        }
        file.mkdirs();

        String finalCmd = DataMigratorUtil
                .paramsAssignment(mysqldump,"?", "", config.getIp(), String.valueOf(config.getPort()), config.getUser(),
                config.getPassword(), task.getSchema(), task.getTable(), makeWhere(task), file.getPath());
      String result=  ProcessUtil.execReturnString(finalCmd);
        int logIndex = result.indexOf("MASTER_LOG_FILE='");
        int logPosIndex = result.indexOf("MASTER_LOG_POS=");
        String logFile=result.substring(logIndex +17,logIndex +17+result.substring(logIndex +17).indexOf("'")) ;
        String logPos=result.substring(logPosIndex +15,logPosIndex +15+result.substring(logPosIndex +15).indexOf(";")) ;

            File dataFile = new File(file, task.getTable() + ".txt");
            if(dataFile.length()>0) {
                String xxx = Files.toString(dataFile, Charset.forName("UTF-8"));
                loaddataToDn(dataFile, task.getTo(), task.getTable());
            }
            pushMsgToZK(task.getZkpath(),task.getFrom()+"-"+task.getTo(),1,"sucess",logFile,logPos);
            DataMigratorUtil.deleteDir(file);
            sucessTask.getAndIncrement();
        } catch (Exception e) {
            try {
                pushMsgToZK(task.getZkpath(),task.getFrom()+"-"+task.getTo(),0,e.getMessage(),"","");
            } catch (Exception e1) {
            }
            LOGGER.error("error:",e);
        }  finally {
            latch.countDown();
        }


    }


    private void pushMsgToZK(String rootZkPath,String child,int status,String msg,String binlogFile,String pos) throws Exception {
        String path = rootZkPath + "/" + child;
        TaskStatus taskStatus=new TaskStatus();
        taskStatus.setMsg(msg);
        taskStatus.setStatus(status);

        if(ZKUtils.getConnection().checkExists().forPath(path)==null )
        {
           ZKUtils.getConnection().create().forPath(path, JSON.toJSONBytes(taskStatus)) ;
        } else{
            ZKUtils.getConnection().setData().forPath(path, JSON.toJSONBytes(taskStatus)) ;
        }
    }

    private void loaddataToDn(File loaddataFile,String toDn,String table) throws SQLException, IOException {
        PhysicalDBNode dbNode = MycatServer.getInstance().getConfig().getDataNodes().get(toDn);
        PhysicalDBPool dbPool = dbNode.getDbPool();
        PhysicalDatasource datasource = dbPool.getSources()[dbPool.getActivedIndex()];
        DBHostConfig config = datasource.getConfig();
        Connection con = null;
        try {
            con =  DriverManager.getConnection("jdbc:mysql://"+config.getUrl()+"/"+dbNode.getDatabase(),config.getUser(),config.getPassword());
            String sql = "load data local infile '"+loaddataFile.getPath().replace("\\","//")+"' replace into table "+table+" character set 'utf8mb4'  fields terminated by ','  enclosed by '\"'  ESCAPED BY '\\\\'  lines terminated by '\\n'";
            System.out.println(sql);
            JdbcUtils.execute(con,sql, new ArrayList<>());
        } finally{
            JdbcUtils.close(con);
        }
    }

    private String makeWhere(MigrateTask task) {
        List<String> whereList = new ArrayList<>();
        List<Range> slotRanges = task.getSlots();
        for (Range slotRange : slotRanges) {
            if (slotRange.start == slotRange.end) {
                whereList.add("_slot =" + slotRange.start);
            } else {
                whereList.add("_slot >=" + slotRange.start + " and _slot <=" + slotRange.end);
            }
        }

        return Joiner.on(" and ").join(whereList);
    }

    private static String querySecurePath(DBHostConfig config  )  {
        List<Map<String, Object>> list=null;
        String path = null;
        Connection con = null;
        try {
            con =  DriverManager.getConnection("jdbc:mysql://"+config.getUrl(),config.getUser(),config.getPassword());
            list = executeQuery(con, "show variables like 'secure_file_priv'");
            if(list!=null&&list.size()==1)
            path = (String) list.get(0).get("Value");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }finally{
            JdbcUtils.close(con);
        }
        return path;
    }

    public static void main(String[] args) {
        String result="\n" + "--\n" + "-- Position to start replication or point-in-time recovery from\n" + "--\n"
                + "\n" + "CHANGE MASTER TO MASTER_LOG_FILE='NANGE-PC-bin.000021', MASTER_LOG_POS=154;\n";
        int logIndex = result.indexOf("MASTER_LOG_FILE='");
        int logPosIndex = result.indexOf("MASTER_LOG_POS=");
        String logFile=result.substring(logIndex +17,logIndex +17+result.substring(logIndex +17).indexOf("'")) ;
        String logPos=result.substring(logPosIndex +15,logPosIndex +15+result.substring(logPosIndex +15).indexOf(";")) ;
        System.out.println();
    }
}
