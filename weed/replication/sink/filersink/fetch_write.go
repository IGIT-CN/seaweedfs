package filersink

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"google.golang.org/grpc"

	"github.com/chrislusf/seaweedfs/weed/glog"
	"github.com/chrislusf/seaweedfs/weed/operation"
	"github.com/chrislusf/seaweedfs/weed/pb/filer_pb"
	"github.com/chrislusf/seaweedfs/weed/security"
	"github.com/chrislusf/seaweedfs/weed/util"
)

func (fs *FilerSink) replicateChunks(sourceChunks []*filer_pb.FileChunk, dir string) (replicatedChunks []*filer_pb.FileChunk, err error) {
	if len(sourceChunks) == 0 {
		return
	}
	var wg sync.WaitGroup
	for _, sourceChunk := range sourceChunks {
		wg.Add(1)
		go func(chunk *filer_pb.FileChunk) {
			defer wg.Done()
			replicatedChunk, e := fs.replicateOneChunk(chunk, dir)
			if e != nil {
				err = e
			}
			replicatedChunks = append(replicatedChunks, replicatedChunk)
		}(sourceChunk)
	}
	wg.Wait()

	return
}

func (fs *FilerSink) replicateOneChunk(sourceChunk *filer_pb.FileChunk, dir string) (*filer_pb.FileChunk, error) {

	fileId, err := fs.fetchAndWrite(sourceChunk, dir)
	if err != nil {
		return nil, fmt.Errorf("copy %s: %v", sourceChunk.GetFileIdString(), err)
	}

	return &filer_pb.FileChunk{
		FileId:       fileId,
		Offset:       sourceChunk.Offset,
		Size:         sourceChunk.Size,
		Mtime:        sourceChunk.Mtime,
		ETag:         sourceChunk.ETag,
		SourceFileId: sourceChunk.GetFileIdString(),
	}, nil
}

func (fs *FilerSink) fetchAndWrite(sourceChunk *filer_pb.FileChunk, dir string) (fileId string, err error) {

	filename, header, readCloser, err := fs.filerSource.ReadPart(sourceChunk.GetFileIdString())
	if err != nil {
		return "", fmt.Errorf("read part %s: %v", sourceChunk.GetFileIdString(), err)
	}
	defer readCloser.Close()

	var host string
	var auth security.EncodedJwt

	if err := fs.withFilerClient(func(client filer_pb.SeaweedFilerClient) error {

		request := &filer_pb.AssignVolumeRequest{
			Count:       1,
			Replication: fs.replication,
			Collection:  fs.collection,
			TtlSec:      fs.ttlSec,
			DataCenter:  fs.dataCenter,
			ParentPath:  dir,
		}

		resp, err := client.AssignVolume(context.Background(), request)
		if err != nil {
			glog.V(0).Infof("assign volume failure %v: %v", request, err)
			return err
		}
		if resp.Error != "" {
			return fmt.Errorf("assign volume failure %v: %v", request, resp.Error)
		}

		fileId, host, auth = resp.FileId, resp.Url, security.EncodedJwt(resp.Auth)

		return nil
	}); err != nil {
		return "", fmt.Errorf("filerGrpcAddress assign volume: %v", err)
	}

	fileUrl := fmt.Sprintf("http://%s/%s", host, fileId)

	glog.V(4).Infof("replicating %s to %s header:%+v", filename, fileUrl, header)

	uploadResult, err := operation.Upload(fileUrl, filename, readCloser,
		"gzip" == header.Get("Content-Encoding"), header.Get("Content-Type"), nil, auth)
	if err != nil {
		glog.V(0).Infof("upload data %v to %s: %v", filename, fileUrl, err)
		return "", fmt.Errorf("upload data: %v", err)
	}
	if uploadResult.Error != "" {
		glog.V(0).Infof("upload failure %v to %s: %v", filename, fileUrl, err)
		return "", fmt.Errorf("upload result: %v", uploadResult.Error)
	}

	return
}

func (fs *FilerSink) withFilerClient(fn func(filer_pb.SeaweedFilerClient) error) error {

	return util.WithCachedGrpcClient(func(grpcConnection *grpc.ClientConn) error {
		client := filer_pb.NewSeaweedFilerClient(grpcConnection)
		return fn(client)
	}, fs.grpcAddress, fs.grpcDialOption)

}

func volumeId(fileId string) string {
	lastCommaIndex := strings.LastIndex(fileId, ",")
	if lastCommaIndex > 0 {
		return fileId[:lastCommaIndex]
	}
	return fileId
}
