package filesys

import (
	"context"

	"github.com/chrislusf/seaweedfs/weed/filer2"
	"github.com/chrislusf/seaweedfs/weed/glog"
	"github.com/chrislusf/seaweedfs/weed/pb/filer_pb"
	"github.com/seaweedfs/fuse"
	"github.com/seaweedfs/fuse/fs"
)

func (dir *Dir) Rename(ctx context.Context, req *fuse.RenameRequest, newDirectory fs.Node) error {

	newDir := newDirectory.(*Dir)
	glog.V(4).Infof("dir Rename %s/%s => %s/%s", dir.Path, req.OldName, newDir.Path, req.NewName)

	err := dir.wfs.WithFilerClient(func(client filer_pb.SeaweedFilerClient) error {

		request := &filer_pb.AtomicRenameEntryRequest{
			OldDirectory: dir.Path,
			OldName:      req.OldName,
			NewDirectory: newDir.Path,
			NewName:      req.NewName,
		}

		_, err := client.AtomicRenameEntry(context.Background(), request)
		if err != nil {
			glog.V(0).Infof("dir Rename %s/%s => %s/%s : %v", dir.Path, req.OldName, newDir.Path, req.NewName, err)
			return fuse.EIO
		}

		return nil

	})

	if err == nil {
		newPath := filer2.NewFullPath(newDir.Path, req.NewName)
		oldPath := filer2.NewFullPath(dir.Path, req.OldName)
		dir.wfs.cacheDelete(newPath)
		dir.wfs.cacheDelete(oldPath)

		oldFileNode := dir.wfs.getNode(oldPath, func() fs.Node {
			return nil
		})
		newDirNode := dir.wfs.getNode(filer2.FullPath(dir.Path), func() fs.Node {
			return nil
		})
		dir.wfs.forgetNode(newPath)
		dir.wfs.forgetNode(oldPath)
		if oldFileNode != nil && newDirNode != nil {
			oldFile := oldFileNode.(*File)
			oldFile.Name = req.NewName
			oldFile.dir = newDirNode.(*Dir)
			dir.wfs.getNode(newPath, func() fs.Node {
				return oldFile
			})

		}
	}

	return err
}
