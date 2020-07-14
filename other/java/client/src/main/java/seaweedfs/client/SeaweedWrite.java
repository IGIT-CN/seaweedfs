package seaweedfs.client;

import com.google.protobuf.ByteString;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

public class SeaweedWrite {

    private static final SecureRandom random = new SecureRandom();

    public static void writeData(FilerProto.Entry.Builder entry,
                                 final String replication,
                                 final FilerGrpcClient filerGrpcClient,
                                 final long offset,
                                 final byte[] bytes,
                                 final long bytesOffset, final long bytesLength) throws IOException {
        FilerProto.AssignVolumeResponse response = filerGrpcClient.getBlockingStub().assignVolume(
                FilerProto.AssignVolumeRequest.newBuilder()
                        .setCollection(filerGrpcClient.getCollection())
                        .setReplication(replication == null ? filerGrpcClient.getReplication() : replication)
                        .setDataCenter("")
                        .setTtlSec(0)
                        .build());
        String fileId = response.getFileId();
        String url = response.getUrl();
        String auth = response.getAuth();
        String targetUrl = String.format("http://%s/%s", url, fileId);

        ByteString cipherKeyString = com.google.protobuf.ByteString.EMPTY;
        byte[] cipherKey = null;
        if (filerGrpcClient.isCipher()) {
            cipherKey = genCipherKey();
            cipherKeyString = ByteString.copyFrom(cipherKey);
        }

        String etag = multipartUpload(targetUrl, auth, bytes, bytesOffset, bytesLength, cipherKey);

        synchronized (entry) {
            entry.addChunks(FilerProto.FileChunk.newBuilder()
                    .setFileId(fileId)
                    .setOffset(offset)
                    .setSize(bytesLength)
                    .setMtime(System.currentTimeMillis() / 10000L)
                    .setETag(etag)
                    .setCipherKey(cipherKeyString)
            );
        }

        // cache fileId ~ bytes
        SeaweedRead.chunkCache.setChunk(fileId, bytes);

    }

    public static void writeMeta(final FilerGrpcClient filerGrpcClient,
                                 final String parentDirectory, final FilerProto.Entry.Builder entry) {
        synchronized (entry) {
            filerGrpcClient.getBlockingStub().createEntry(
                    FilerProto.CreateEntryRequest.newBuilder()
                            .setDirectory(parentDirectory)
                            .setEntry(entry)
                            .build()
            );
        }
    }

    private static String multipartUpload(String targetUrl,
                                          String auth,
                                          final byte[] bytes,
                                          final long bytesOffset, final long bytesLength,
                                          byte[] cipherKey) throws IOException {

        InputStream inputStream = null;
        if (cipherKey == null || cipherKey.length == 0) {
            inputStream = new ByteArrayInputStream(bytes, (int) bytesOffset, (int) bytesLength);
        } else {
            try {
                byte[] encryptedBytes = SeaweedCipher.encrypt(bytes, (int) bytesOffset, (int) bytesLength, cipherKey);
                inputStream = new ByteArrayInputStream(encryptedBytes, 0, encryptedBytes.length);
            } catch (Exception e) {
                throw new IOException("fail to encrypt data", e);
            }
        }

        HttpPost post = new HttpPost(targetUrl);
        if (auth != null && auth.length() != 0) {
            post.addHeader("Authorization", "BEARER " + auth);
        }

        post.setEntity(MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addBinaryBody("upload", inputStream)
                .build());

        CloseableHttpResponse response = SeaweedUtil.getClosableHttpClient().execute(post);

        try {

            String etag = response.getLastHeader("ETag").getValue();

            if (etag != null && etag.startsWith("\"") && etag.endsWith("\"")) {
                etag = etag.substring(1, etag.length() - 1);
            }

            EntityUtils.consume(response.getEntity());

            return etag;
        } finally {
            response.close();
            post.releaseConnection();
        }

    }

    private static byte[] genCipherKey() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return b;
    }
}
