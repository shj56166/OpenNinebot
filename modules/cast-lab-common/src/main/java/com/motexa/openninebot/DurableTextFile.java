package com.motexa.openninebot;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
/** Every accepted record is written and synced; no queue eviction or size truncation. */
final class DurableTextFile implements AutoCloseable {
    private final FileOutputStream output;
    private boolean closed;
    DurableTextFile(File file,boolean append)throws IOException {
        File parent=file.getParentFile();
        if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs()&&!parent.isDirectory())throw new IOException("log_directory_unavailable");
        boolean incomplete=false;
        if(append&&file.isFile()&&file.length()>0)try(RandomAccessFile existing=new RandomAccessFile(file,"r")){existing.seek(existing.length()-1);incomplete=existing.read()!='\n';}
        output=new FileOutputStream(file,append);
        // Preserve an interrupted tail and separate the next record; never
        // truncate it or concatenate fresh JSON into partially written bytes.
        if(incomplete){output.write('\n');output.getFD().sync();}
    }
    synchronized void appendLine(String line,boolean force)throws IOException {
        ensureOpen();output.write(((line==null?"":line)+"\n").getBytes(StandardCharsets.UTF_8));output.flush();if(force)output.getFD().sync();
    }
    synchronized void force()throws IOException {ensureOpen();output.flush();output.getFD().sync();}
    public synchronized void close()throws IOException {
        if(closed)return;try{output.flush();output.getFD().sync();}finally{closed=true;output.close();}
    }
    private void ensureOpen()throws IOException {if(closed)throw new IOException("log_closed");}
}
