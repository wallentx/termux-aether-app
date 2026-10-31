// A standalone regression probe, built both with and without cgo by validate.py.
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

func main() {
	self, err := os.Executable()
	if err != nil {
		panic(err)
	}
	if len(os.Args) > 1 && os.Args[1] == "exec" {
		if err := syscall.Exec(self, []string{"reexec-alias", "report", "one two", ""}, os.Environ()); err != nil {
			panic(err)
		}
	}
	if len(os.Args) > 1 && os.Args[1] == "spawn" {
		cmd := exec.Command(self, "report", "one two", "", "first")
		cmd.Args[0] = "child-alias"
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Run(); err != nil {
			panic(err)
		}
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "command" {
		cmd := exec.Command(os.Args[2], os.Args[3:]...)
		if dir := os.Getenv("AETHER_TEST_DIR"); dir != "" {
			cmd.Dir = dir
		}
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Run(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	actual, err := filepath.EvalSymlinks(self)
	if err != nil {
		panic(err)
	}
	json.NewEncoder(os.Stdout).Encode(struct {
		Args       []string
		Executable string
	}{os.Args, actual})
}
