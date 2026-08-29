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
}
