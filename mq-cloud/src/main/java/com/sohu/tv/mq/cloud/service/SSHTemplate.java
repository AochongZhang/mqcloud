package com.sohu.tv.mq.cloud.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sohu.tv.mq.cloud.util.MQCloudConfigHelper;
import com.sohu.tv.mq.cloud.util.SSHException;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SCPClient;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;

/**
 * SSH操作模板类
 * 
 * @Description:
 * @author yongfeigao
 * @date 2018年7月18日
 */
@Service
public class SSHTemplate {
    private static final Logger logger = LoggerFactory.getLogger(SSHTemplate.class);

    @Autowired
    private MQCloudConfigHelper mqCloudConfigHelper;
    
    @Autowired
    private GenericKeyedObjectPool<String, Connection> sshPool;

    private static ThreadPoolExecutor taskPool = new ThreadPoolExecutor(
            200, 200, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(1000),
            new ThreadFactoryBuilder().setNameFormat("SSH-%d").setDaemon(true).build());

    private static ThreadPoolExecutor openSessionTaskPool = new ThreadPoolExecutor(
            100, 100, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(100),
            new ThreadFactoryBuilder().setNameFormat("OpenSSH-%d").setDaemon(true).build());

    /**
     * 校验ip是否合法
     * 
     * @param ip
     * @param callback
     * @return
     * @throws SSHException
     */
    public boolean validate(String ip) throws SSHException {
        SSHResult result = execute(ip,
                new SSHCallback() {
                    public SSHResult call(SSHSession session) {
                        return session.executeCommand("date");
                    }
                });
        return result.isSuccess();
    }

    /**
     * 通过回调执行命令
     * 
     * @param ip
     * @param callback 可以使用Session执行多个命令
     * @throws SSHException
     */
    public SSHResult execute(String ip, SSHCallback callback) throws SSHException {
        Connection conn = null;
        try {
            conn = sshPool.borrowObject(ip);
            return callback.call(new SSHSession(conn, ip));
        } catch (Exception e) {
            throw new SSHException("SSH err: " + e.getMessage(), e);
        } finally {
            close(ip, conn);
        }
    }

    private DefaultLineProcessor generateDefaultLineProcessor(StringBuilder buffer) {
        return new DefaultLineProcessor() {
            public void process(String line, int lineNum) throws Exception {
                if (lineNum > 1) {
                    buffer.append(System.lineSeparator());
                }
                buffer.append(line);
            }
        };
    }

    /**
     * 从流中获取内容
     * 
     * @param is
     */
    private void processStream(InputStream is, LineProcessor lineProcessor) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new StreamGobbler(is)));
            String line = null;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                try {
                    lineProcessor.process(line, lineNum);
                } catch (Exception e) {
                    logger.error("err line:" + line, e);
                }
                if (lineProcessor instanceof DefaultLineProcessor) {
                    ((DefaultLineProcessor) lineProcessor).setLineNum(lineNum);
                }
                lineNum++;
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } finally {
            close(reader);
        }
    }

    private void close(BufferedReader read) {
        if (read != null) {
            try {
                read.close();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void close(String ip, Connection conn) {
        if (conn != null) {
            try {
                sshPool.returnObject(ip, conn);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private static void close(Session session) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 可以调用多次executeCommand， 并返回结果
     */
    public class SSHSession {
        private String address;
        private Connection conn;

        private SSHSession(Connection conn, String address) {
            this.conn = conn;
            this.address = address;
        }

        /**
         * 执行命令并返回结果，可以执行多次
         * 
         * @param cmd
         * @return 执行成功Result为true，并携带返回信息,返回信息可能为null 执行失败Result为false，并携带失败信息
         *         执行异常Result为false，并携带异常
         */
        public SSHResult executeCommand(String cmd) {
            return executeCommand(cmd, mqCloudConfigHelper.getServerOPTimeout());
        }

        public SSHResult executeCommand(String cmd, int timoutMillis) {
            return executeCommand(cmd, null, timoutMillis);
        }

        public SSHResult executeCommand(String cmd, LineProcessor lineProcessor) {
            return executeCommand(cmd, lineProcessor, mqCloudConfigHelper.getServerOPTimeout());
        }

        /**
         * 执行命令并返回结果，可以执行多次
         * 
         * @param cmd
         * @param lineProcessor 回调处理行
         * @return 如果lineProcessor不为null,那么永远返回Result.true
         */
        public SSHResult executeCommand(String cmd, LineProcessor lineProcessor, int timoutMillis) {
            Session session = null;
            try {
                Future<Session> future = openSessionTaskPool.submit(new Callable<Session>() {
                    public Session call() throws Exception {
                        Session openedSession = conn.openSession();
                        return openedSession;
                    }
                });
                try {
                    session = future.get(timoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    future.cancel(true);
                    logger.error("cancel openedSession task!", e);
                } catch (ExecutionException e) {
                    future.cancel(true);
                    logger.error("cancel openedSession task!", e);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    logger.error("cancel openedSession task!", e);
                }
                if (session == null) {
                    throw new SSHException("timeout:" + timoutMillis);
                }
                return executeCommand(session, cmd, timoutMillis, lineProcessor);
            } catch (Exception e) {
                logger.error("execute ip:" + conn.getHostname() + " cmd:" + cmd, e);
                return new SSHResult(e);
            } finally {
                close(session);
            }
        }

        public SSHResult executeCommand(final Session session, final String cmd,
                final int timoutMillis, final LineProcessor lineProcessor) throws Exception {
            Future<SSHResult> future = taskPool.submit(new Callable<SSHResult>() {
                public SSHResult call() throws Exception {
                    session.execCommand(cmd);
                    LineProcessor tmpLP = lineProcessor;
                    // 如果客户端需要进行行处理，则直接进行回调
                    if (tmpLP != null) {
                        processStream(session.getStdout(), tmpLP);
                    } else {
                        StringBuilder buffer = new StringBuilder();
                        tmpLP = generateDefaultLineProcessor(buffer);
                        processStream(session.getStdout(), tmpLP);
                        if (buffer.length() > 0) {
                            return new SSHResult(true, buffer.toString());
                        }
                    }
                    if(tmpLP.lineNum() == 0) {
                        // 返回为null代表可能有异常，需要检测标准错误输出，以便记录日志
                        SSHResult errResult = tryLogError(session.getStderr(), cmd);
                        if (errResult != null) {
                            return errResult;
                        }
                    }
                    return new SSHResult(true, null);
                }
            });
            SSHResult rst = null;
            try {
                rst = future.get(timoutMillis, TimeUnit.MILLISECONDS);
                future.cancel(true);
            } catch (TimeoutException e) {
                logger.error("execute timeout:{} ip:{} {}", timoutMillis, conn.getHostname(), cmd);
                throw new SSHException(e);
            }
            return rst;
        }

        private SSHResult tryLogError(InputStream is, String cmd) {
            StringBuilder buffer = new StringBuilder();
            LineProcessor lp = generateDefaultLineProcessor(buffer);
            processStream(is, lp);
            String errInfo = buffer.length() > 0 ? buffer.toString() : null;
            if (errInfo != null) {
                logger.error("address " + address + " execute cmd:({}), err:{}", cmd, errInfo);
                return new SSHResult(false, errInfo);
            }
            return null;
        }

        /**
         * Copy a set of local files to a remote directory, uses the specified
         * mode when creating the file on the remote side.
         * 
         * @param localFiles Path and name of local file.
         * @param remoteFiles name of remote file.
         * @param remoteTargetDirectory Remote target directory. Use an empty
         *            string to specify the default directory.
         * @param mode a four digit string (e.g., 0644, see "man chmod", "man
         *            open")
         * @throws IOException
         */
        public SSHResult scp(String[] localFiles, String[] remoteFiles, String remoteTargetDirectory, String mode) {
            try {
                SCPClient client = conn.createSCPClient();
                client.put(localFiles, remoteFiles, remoteTargetDirectory, mode);
                return new SSHResult(true);
            } catch (Exception e) {
                logger.error("scp local=" + Arrays.toString(localFiles) + " to " +
                        remoteTargetDirectory + " remote=" + Arrays.toString(remoteFiles) + " err", e);
                return new SSHResult(e);
            }
        }

        public SSHResult scpToDir(String localFile, String remoteTargetDirectory) {
            return scpToDir(localFile, remoteTargetDirectory, "0744");
        }

        public SSHResult scpToDir(String localFile, String remoteTargetDirectory, String mode) {
            return scp(new String[] {localFile}, null, remoteTargetDirectory, mode);
        }
        
        public SSHResult scpToDir(byte[] data, String remoteFileName, String remoteTargetDirectory) {
            try {
                SCPClient client = conn.createSCPClient();
                client.put(data, remoteFileName, remoteTargetDirectory);
                return new SSHResult(true);
            } catch (Exception e) {
                logger.error("scp byte to " + remoteTargetDirectory + " remote=" + remoteFileName + " err", e);
                return new SSHResult(e);
            }
        }

        public SSHResult scpToDir(String[] localFile, String remoteTargetDirectory) {
            return scp(localFile, null, remoteTargetDirectory, "0744");
        }

        public SSHResult scpToFile(String localFile, String remoteFile, String remoteTargetDirectory) {
            return scpToFile(localFile, remoteFile, remoteTargetDirectory, "0744");
        }

        public SSHResult scpToFile(String localFile, String remoteFile, String remoteTargetDirectory, String mode) {
            return scp(new String[] {localFile}, new String[] {remoteFile}, remoteTargetDirectory, "0744");
        }
    }

    /**
     * 结果封装
     */
    public class SSHResult {
        private boolean success;
        private String result;
        private Exception excetion;

        public SSHResult(boolean success) {
            this.success = success;
        }

        public SSHResult(boolean success, String result) {
            this.success = success;
            this.result = result;
        }

        public SSHResult(Exception excetion) {
            this.success = false;
            this.excetion = excetion;
        }

        public Exception getExcetion() {
            return excetion;
        }

        public void setExcetion(Exception excetion) {
            this.excetion = excetion;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        @Override
        public String toString() {
            return "Result [success=" + success + ", result=" + result
                    + ", excetion=" + excetion + "]";
        }
    }

    /**
     * 执行命令回调
     */
    public interface SSHCallback {
        /**
         * 执行回调
         * 
         * @param session
         */
        SSHResult call(SSHSession session);
    }

    /**
     * 从流中直接解析数据
     */
    public static interface LineProcessor {
        /**
         * 处理行
         * 
         * @param line 内容
         * @param lineNum 行号，从1开始
         * @throws Exception
         */
        void process(String line, int lineNum) throws Exception;

        /**
         * 返回内容的行数，如果为0需要检测错误流
         * @return
         */
        int lineNum();
    }

    public static abstract class DefaultLineProcessor implements LineProcessor {
        protected int lineNum;

        @Override
        public int lineNum() {
            return lineNum;
        }

        public void setLineNum(int lineNum) {
            this.lineNum = lineNum;
        }
    }
}
