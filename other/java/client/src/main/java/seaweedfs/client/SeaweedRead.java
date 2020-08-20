package seaweedfs.client;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class SeaweedRead {

    private static final Logger LOG = LoggerFactory.getLogger(SeaweedRead.class);

    static ChunkCache chunkCache = new ChunkCache(4);

    // returns bytesRead
    public static long read(FilerGrpcClient filerGrpcClient, List<VisibleInterval> visibleIntervals,
                            final long position, final byte[] buffer, final int bufferOffset,
                            final int bufferLength, final long fileSize) throws IOException {

        List<ChunkView> chunkViews = viewFromVisibles(visibleIntervals, position, bufferLength);

        FilerProto.LookupVolumeRequest.Builder lookupRequest = FilerProto.LookupVolumeRequest.newBuilder();
        for (ChunkView chunkView : chunkViews) {
            String vid = parseVolumeId(chunkView.fileId);
            lookupRequest.addVolumeIds(vid);
        }

        FilerProto.LookupVolumeResponse lookupResponse = filerGrpcClient
                .getBlockingStub().lookupVolume(lookupRequest.build());

        Map<String, FilerProto.Locations> vid2Locations = lookupResponse.getLocationsMapMap();

        //TODO parallel this
        long readCount = 0;
        int startOffset = bufferOffset;
        for (ChunkView chunkView : chunkViews) {

            if (startOffset < chunkView.logicOffset) {
                long gap = chunkView.logicOffset - startOffset;
                LOG.debug("zero [{},{})", startOffset, startOffset + gap);
                readCount += gap;
                startOffset += gap;
            }

            FilerProto.Locations locations = vid2Locations.get(parseVolumeId(chunkView.fileId));
            if (locations == null || locations.getLocationsCount() == 0) {
                LOG.error("failed to locate {}", chunkView.fileId);
                // log here!
                return 0;
            }

            int len = readChunkView(position, buffer, startOffset, chunkView, locations);

            LOG.debug("read [{},{}) {} size {}", startOffset, startOffset + len, chunkView.fileId, chunkView.size);

            readCount += len;
            startOffset += len;

        }

        long limit = Math.min(bufferLength, fileSize);

        if (startOffset < limit) {
            long gap = limit - startOffset;
            LOG.debug("zero2 [{},{})", startOffset, startOffset + gap);
            readCount += gap;
            startOffset += gap;
        }

        return readCount;
    }

    private static int readChunkView(long position, byte[] buffer, int startOffset, ChunkView chunkView, FilerProto.Locations locations) throws IOException {

        byte[] chunkData = chunkCache.getChunk(chunkView.fileId);

        if (chunkData == null) {
            chunkData = doFetchFullChunkData(chunkView, locations);
            chunkCache.setChunk(chunkView.fileId, chunkData);
        }

        int len = (int) chunkView.size;
        LOG.debug("readChunkView fid:{} chunkData.length:{} chunkView.offset:{} buffer.length:{} startOffset:{} len:{}",
                chunkView.fileId, chunkData.length, chunkView.offset, buffer.length, startOffset, len);
        System.arraycopy(chunkData, startOffset - (int) (chunkView.logicOffset - chunkView.offset), buffer, startOffset, len);

        return len;
    }

    public static byte[] doFetchFullChunkData(ChunkView chunkView, FilerProto.Locations locations) throws IOException {

        HttpGet request = new HttpGet(
                String.format("http://%s/%s", locations.getLocations(0).getUrl(), chunkView.fileId));

        request.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip");

        byte[] data = null;

        CloseableHttpResponse response = SeaweedUtil.getClosableHttpClient().execute(request);

        try {
            HttpEntity entity = response.getEntity();

            Header contentEncodingHeader = entity.getContentEncoding();

            if (contentEncodingHeader != null) {
                HeaderElement[] encodings = contentEncodingHeader.getElements();
                for (int i = 0; i < encodings.length; i++) {
                    if (encodings[i].getName().equalsIgnoreCase("gzip")) {
                        entity = new GzipDecompressingEntity(entity);
                        break;
                    }
                }
            }

            data = EntityUtils.toByteArray(entity);

            EntityUtils.consume(entity);

        } finally {
            response.close();
            request.releaseConnection();
        }

        if (chunkView.cipherKey != null && chunkView.cipherKey.length != 0) {
            try {
                data = SeaweedCipher.decrypt(data, chunkView.cipherKey);
            } catch (Exception e) {
                throw new IOException("fail to decrypt", e);
            }
        }

        if (chunkView.isCompressed) {
            data = Gzip.decompress(data);
        }

        LOG.debug("doFetchFullChunkData fid:{} chunkData.length:{}", chunkView.fileId, data.length);

        return data;

    }

    protected static List<ChunkView> viewFromVisibles(List<VisibleInterval> visibleIntervals, long offset, long size) {
        List<ChunkView> views = new ArrayList<>();

        long stop = offset + size;
        for (VisibleInterval chunk : visibleIntervals) {
            long chunkStart = Math.max(offset, chunk.start);
            long chunkStop = Math.min(stop, chunk.stop);
            if (chunkStart < chunkStop) {
                boolean isFullChunk = chunk.isFullChunk && chunk.start == offset && chunk.stop <= stop;
                views.add(new ChunkView(
                        chunk.fileId,
                        chunkStart - chunk.start + chunk.chunkOffset,
                        chunkStop - chunkStart,
                        chunkStart,
                        isFullChunk,
                        chunk.cipherKey,
                        chunk.isCompressed
                ));
            }
        }
        return views;
    }

    public static List<VisibleInterval> nonOverlappingVisibleIntervals(
            final FilerGrpcClient filerGrpcClient, List<FilerProto.FileChunk> chunkList) throws IOException {

        chunkList = FileChunkManifest.resolveChunkManifest(filerGrpcClient, chunkList);

        FilerProto.FileChunk[] chunks = chunkList.toArray(new FilerProto.FileChunk[0]);
        Arrays.sort(chunks, new Comparator<FilerProto.FileChunk>() {
            @Override
            public int compare(FilerProto.FileChunk a, FilerProto.FileChunk b) {
                // if just a.getMtime() - b.getMtime(), it will overflow!
                if (a.getMtime() < b.getMtime()) {
                    return -1;
                } else if (a.getMtime() > b.getMtime()) {
                    return 1;
                }
                return 0;
            }
        });

        List<VisibleInterval> visibles = new ArrayList<>();
        for (FilerProto.FileChunk chunk : chunks) {
            List<VisibleInterval> newVisibles = new ArrayList<>();
            visibles = mergeIntoVisibles(visibles, newVisibles, chunk);
        }

        return visibles;
    }

    private static List<VisibleInterval> mergeIntoVisibles(List<VisibleInterval> visibles,
                                                           List<VisibleInterval> newVisibles,
                                                           FilerProto.FileChunk chunk) {
        VisibleInterval newV = new VisibleInterval(
                chunk.getOffset(),
                chunk.getOffset() + chunk.getSize(),
                chunk.getFileId(),
                chunk.getMtime(),
                0,
                true,
                chunk.getCipherKey().toByteArray(),
                chunk.getIsCompressed()
        );

        // easy cases to speed up
        if (visibles.size() == 0) {
            visibles.add(newV);
            return visibles;
        }
        if (visibles.get(visibles.size() - 1).stop <= chunk.getOffset()) {
            visibles.add(newV);
            return visibles;
        }

        for (VisibleInterval v : visibles) {
            if (v.start < chunk.getOffset() && chunk.getOffset() < v.stop) {
                newVisibles.add(new VisibleInterval(
                        v.start,
                        chunk.getOffset(),
                        v.fileId,
                        v.modifiedTime,
                        v.chunkOffset,
                        false,
                        v.cipherKey,
                        v.isCompressed
                ));
            }
            long chunkStop = chunk.getOffset() + chunk.getSize();
            if (v.start < chunkStop && chunkStop < v.stop) {
                newVisibles.add(new VisibleInterval(
                        chunkStop,
                        v.stop,
                        v.fileId,
                        v.modifiedTime,
                        v.chunkOffset + (chunkStop - v.start),
                        false,
                        v.cipherKey,
                        v.isCompressed
                ));
            }
            if (chunkStop <= v.start || v.stop <= chunk.getOffset()) {
                newVisibles.add(v);
            }
        }
        newVisibles.add(newV);

        // keep everything sorted
        for (int i = newVisibles.size() - 1; i >= 0; i--) {
            if (i > 0 && newV.start < newVisibles.get(i - 1).start) {
                newVisibles.set(i, newVisibles.get(i - 1));
            } else {
                newVisibles.set(i, newV);
                break;
            }
        }

        return newVisibles;
    }

    public static String parseVolumeId(String fileId) {
        int commaIndex = fileId.lastIndexOf(',');
        if (commaIndex > 0) {
            return fileId.substring(0, commaIndex);
        }
        return fileId;
    }

    public static long fileSize(FilerProto.Entry entry) {
        return Math.max(totalSize(entry.getChunksList()), entry.getAttributes().getFileSize());
    }

    public static long totalSize(List<FilerProto.FileChunk> chunksList) {
        long size = 0;
        for (FilerProto.FileChunk chunk : chunksList) {
            long t = chunk.getOffset() + chunk.getSize();
            if (size < t) {
                size = t;
            }
        }
        return size;
    }

    public static class VisibleInterval {
        public final long start;
        public final long stop;
        public final long modifiedTime;
        public final String fileId;
        public final long chunkOffset;
        public final boolean isFullChunk;
        public final byte[] cipherKey;
        public final boolean isCompressed;

        public VisibleInterval(long start, long stop, String fileId, long modifiedTime, long chunkOffset, boolean isFullChunk, byte[] cipherKey, boolean isCompressed) {
            this.start = start;
            this.stop = stop;
            this.modifiedTime = modifiedTime;
            this.fileId = fileId;
            this.chunkOffset = chunkOffset;
            this.isFullChunk = isFullChunk;
            this.cipherKey = cipherKey;
            this.isCompressed = isCompressed;
        }

        @Override
        public String toString() {
            return "VisibleInterval{" +
                    "start=" + start +
                    ", stop=" + stop +
                    ", modifiedTime=" + modifiedTime +
                    ", fileId='" + fileId + '\'' +
                    ", isFullChunk=" + isFullChunk +
                    ", cipherKey=" + Arrays.toString(cipherKey) +
                    ", isCompressed=" + isCompressed +
                    '}';
        }
    }

    public static class ChunkView {
        public final String fileId;
        public final long offset;
        public final long size;
        public final long logicOffset;
        public final boolean isFullChunk;
        public final byte[] cipherKey;
        public final boolean isCompressed;

        public ChunkView(String fileId, long offset, long size, long logicOffset, boolean isFullChunk, byte[] cipherKey, boolean isCompressed) {
            this.fileId = fileId;
            this.offset = offset;
            this.size = size;
            this.logicOffset = logicOffset;
            this.isFullChunk = isFullChunk;
            this.cipherKey = cipherKey;
            this.isCompressed = isCompressed;
        }

        @Override
        public String toString() {
            return "ChunkView{" +
                    "fileId='" + fileId + '\'' +
                    ", offset=" + offset +
                    ", size=" + size +
                    ", logicOffset=" + logicOffset +
                    ", isFullChunk=" + isFullChunk +
                    ", cipherKey=" + Arrays.toString(cipherKey) +
                    ", isCompressed=" + isCompressed +
                    '}';
        }
    }

}
