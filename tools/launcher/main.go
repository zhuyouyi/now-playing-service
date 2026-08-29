package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	dir := "."
	if exe, err := os.Executable(); err == nil {
		dir = filepath.Dir(exe)
	}
	cmd := exec.Command(
		filepath.Join(dir, "jre", "bin", "javaw.exe"),
		"-jar",
		filepath.Join(dir, "now-playing-0.0.1-SNAPSHOT.jar"),
	)
	cmd.Dir = dir
	_ = cmd.Start()
	// 保持为 java 的父进程并常驻，这样客户端退出时 taskkill /T /F 能连带杀掉 java
	_ = cmd.Wait()
}
