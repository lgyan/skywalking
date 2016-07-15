package com.ai.cloud.skywalking.reciever.peresistent;

import com.ai.cloud.skywalking.protocol.SerializedFactory;
import com.ai.cloud.skywalking.protocol.TransportPackager;
import com.ai.cloud.skywalking.protocol.common.AbstractDataSerializable;
import com.ai.cloud.skywalking.protocol.exception.ConvertFailedException;
import com.ai.cloud.skywalking.reciever.conf.Config;
import com.ai.cloud.skywalking.reciever.processor.IProcessor;
import com.ai.cloud.skywalking.reciever.processor.ProcessorFactory;
import com.ai.cloud.skywalking.reciever.selfexamination.ServerHealthCollector;
import com.ai.cloud.skywalking.reciever.selfexamination.ServerHeathReading;
import org.apache.commons.io.comparator.NameFileComparator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PersistenceThread extends Thread {

    private Logger logger     = LogManager.getLogger(PersistenceThread.class);
    private File   bufferFile = null;

    public PersistenceThread(int trdIndex) {
        super("PersistentThread" + trdIndex);
    }

    @Override
    public void run() {
        while (true) {
            bufferFile = chooseDealBufferFile();

            if (bufferFile == null) {
                try {
                    Thread.sleep(Config.Persistence.SWITCH_FILE_WAIT_TIME);
                } catch (InterruptedException e) {
                    logger.error("Failure sleep.", e);
                }
                continue;
            }

            int offset = acquireOffset();

            BufferBalePlucker bufferBalePlucker = new BufferBalePlucker(bufferFile, offset);
            while (bufferBalePlucker.hasNextBufferBale()) {
                try {
                    Map<Integer, List<AbstractDataSerializable>> spans = unSerializeSpans(bufferBalePlucker.pluck());
                    System.out.println(spans.size());
                    //handleSpans(spans);
                } catch (ConvertFailedException e) {
                    bufferBalePlucker.skipToNextBufferBale();
                } catch (IOException e) {
                    logger.error("The data file I/O exception.", e);
                    ServerHealthCollector.getCurrentHeathReading(null)
                            .updateData(ServerHeathReading.ERROR, e.getMessage());
                }
            }

            try {
                bufferBalePlucker.close();
            } catch (IOException e) {
                logger.error("The data file I/O exception.", e);
                ServerHealthCollector.getCurrentHeathReading(null).updateData(ServerHeathReading.ERROR, e.getMessage());
            }

            try {
                Thread.sleep(Config.Persistence.SWITCH_FILE_WAIT_TIME);
            } catch (InterruptedException e) {
                logger.error("Failure sleep.", e);
            }
        }
    }

    private int acquireOffset() {
        int offset;
        offset = MemoryRegister.instance().getOffSet(bufferFile.getName());
        if (offset == -1 || offset == 0) {
            offset = 0;
        }
        return offset;
    }

    private void handleSpans(Map<Integer, List<AbstractDataSerializable>> spans) {
        for (Map.Entry<Integer, List<AbstractDataSerializable>> entry : spans.entrySet()) {
            IProcessor processor = ProcessorFactory.chooseProcessor(entry.getKey());
            if (processor != null) {
                processor.process(entry.getValue());
            }
        }
    }

    private Map<Integer, List<AbstractDataSerializable>> unSerializeSpans(byte[] byteData)
            throws ConvertFailedException {
        Map<Integer, List<AbstractDataSerializable>> spans = new HashMap<Integer, List<AbstractDataSerializable>>();
        if (byteData == null || byteData.length == 0) {
            return spans;
        }
        List<byte[]> serializeData = TransportPackager.unpackDataBody(byteData);
        for (byte[] dataBytes : serializeData) {
            AbstractDataSerializable abstractDataSerializable = SerializedFactory.unSerialize(dataBytes);
            if (spans.get(abstractDataSerializable.getDataType()) == null) {
                spans.put(abstractDataSerializable.getDataType(), new ArrayList<AbstractDataSerializable>());
            }
            spans.get(abstractDataSerializable.getDataType()).add(abstractDataSerializable);
        }

        return spans;
    }

    private File chooseDealBufferFile() {
        File file1 = null;
        File parentDir = new File(Config.Buffer.DATA_BUFFER_FILE_PARENT_DIR);
        NameFileComparator sizeComparator = new NameFileComparator();
        File[] dataFileList = sizeComparator.sort(parentDir.listFiles());

        if (dataFileList == null) {
            return null;
        }

        for (File file : dataFileList) {
            if (file.getName().startsWith(".")) {
                continue;
            }
            if (MemoryRegister.instance().doRegister(file.getName()) == null) {
                if (logger.isDebugEnabled())
                    logger.debug("The file [{}] is being used by another thread ", file);
                continue;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Begin to deal data file [{}]", file.getName());
            }
            file1 = file;
            break;
        }

        return file1;
    }
}