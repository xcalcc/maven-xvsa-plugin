#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import logging
import subprocess

logging.basicConfig(level=logging.DEBUG)

class DepNode:
  def __init__(self, dep_info):
    self.__children = []
    self.__dep_info = dep_info
    info = dep_info.split(':')
    self.__group_id = info[0]
    self.__artifact_id = info[1]
    self.__packaging = info[2]
    if len(info) == 5:
      # this is the part of the version
      self.__classifier = None
      self.__version = info[3]
    elif len(info) == 6:
      # this is the part of the version
      self.__classifier = info[3]
      self.__version = info[4]
    else:
      assert False, ("Not supported depencency info, info: %s" % dep_info)

  def add_child(self, child):
    self.__children.append(child)

  def children(self):
    return self.__children

  def is_leaf(self):
    return len(self.__children) == 0

  def pom_name(self):
    return "%s-%s.pom" % (self.__artifact_id, self.__version)

  def lib_name(self):
    if self.__classifier is None:
      return "%s-%s.%s" % (self.__artifact_id, self.__version, self.__packaging)
    else:
      return "%s-%s-%s.%s" % (self.__artifact_id, self.__version, self.__classifier, self.__packaging)

  def group_id(self):
    return self.__group_id

  def artifact_id(self):
    return self.__artifact_id
  
  def packaging(self):
    return self.__packaging
  
  def version(self):
    return self.__version

  def classifier(self):
    return self.__classifier
  
  def __dump_with_indention(self, file, indent):
    indent_str = "\t" * indent
    file.write("%s%s\n" % (indent_str, self.__dep_info))
    if not self.is_leaf():
      for child in self.__children:
        child.__dump_with_indention(file, indent + 1)

  def dump(self, file):
    self.__dump_with_indention(file, 0)

  def __str__(self):
    return self.__dep_info

def get_node_level(line):
  level = 0
  index = 0
  while index <= len(line):
    current_str = line[index:]
    if current_str.startswith("+-") or current_str.startswith("\\-"):
      level += 1
      return level
    elif current_str.startswith("|  ") or current_str.startswith("   "):
      level += 1
      index += 3
    else:
      break
  return index

def push_to_stack(stack, node_tup):
  logging.debug("Before pop, stack size: %d, level: %d, node: %s" % (len(stack), node_tup[1], node_tup[0]))
  if len(stack) == 0:
    stack.append(node_tup)
    return
  # pop the nodes that the level is great or equal than the current node
  # those nodes are current node's brother and the brother's children
  for i in range(len(stack) - 1, -1, -1):
    frame_tup = stack[i]
    if frame_tup[1] >= node_tup[1]:
      stack.pop(i)
  logging.debug("After pop, stack size: %d" % len(stack))
  if len(stack) != 0:
    stack[len(stack) - 1][0].add_child(node_tup[0])
  stack.append(node_tup)

def install_one_lib(dep_file_path, repo_path, dep_node, pom_set):
  lib_name = dep_node.lib_name()
  pom_name = dep_node.pom_name()
  pom_set.add(pom_name)
  lib_path = os.path.join(dep_file_path, lib_name)
  pom_path = os.path.join(dep_file_path, pom_name)
  assert os.path.exists(lib_path), "Library file path not exists, path: %s" % lib_path
  assert os.path.exists(pom_path), "Pom file path not exists, path: %s" % pom_path
  install_args = [
    "mvn", "install:install-file", "-Dfile=%s" % lib_path,
    "-DpomFile=%s" % pom_path, "-DgroupId=%s" % dep_node.group_id(),
    "-DartifactId=%s" % dep_node.artifact_id(), "-Dpackaging=%s" % dep_node.packaging(),
    "-Dversion=%s" % dep_node.version()
  ]
  if dep_node.classifier() is not None:
    install_args.append("-Dclassifier=%s" % dep_node.classifier())
  if repo_path is not None:
    install_args.append("-DlocalRepositoryPath=%s" % repo_path)
  logging.debug("Install depedency, install cmd: %s" % " ".join(install_args))
  subprocess.run(install_args, check=True)

def install_lib(dep_file_path, repo_path, dep_node, pom_set):
  # install current
  install_one_lib(dep_file_path, repo_path, dep_node, pom_set)
  if not dep_node.is_leaf():
    # install child
    for child in dep_node.children():
      install_lib(dep_file_path, repo_path, child, pom_set)

def install_all_libs(dep_file_path, repo_path, deps):
  pom_set = set()
  for dep_node in deps:
    install_lib(dep_file_path, repo_path, dep_node, pom_set)
  # install other pom files
  for f in os.listdir(dep_file_path):
    if not f.endswith(".pom"):
      continue
    pom_path = os.path.join(dep_file_path, f)
    assert os.path.isfile(pom_path), "Pom is not a file, path: %s" % pom_path
    # already handled
    if f in pom_set:
      continue
    install_args = [
      "mvn", "install:install-file", "-Dfile=%s" % pom_path,
      "-DpomFile=%s" % pom_path, "-Dpackaging=pom"
    ]
    if repo_path is not None:
      install_args.append("-DlocalRepositoryPath=%s" % repo_path)
    logging.debug("Install single pom file, install cmd: %s" % " ".join(install_args))
    subprocess.run(install_args, check=True)

def main(dep_tree_file):
  f = open(dep_tree_file, "r")
  start = False
  tree_stack = []
  deps = []
  for line in f:
    if line.startswith("[INFO] io.xc5:xvsa-maven-plugin:maven-plugin"):
      start = True
      continue
    elif start and line.startswith("[INFO] ------------------------------------------------------------------------"):
      break
    if not start:
      continue
    # parse tree
    # trim the header
    line = line.strip()
    line = line[len("[INFO] "):]
    level = get_node_level(line)
    line = line[level * 3:]
    node = DepNode(line)
    if level == 1:
      deps.append(node)
    push_to_stack(tree_stack, (node, level))
  f.close()
  if logging.getLogger().level == logging.DEBUG:
    logging.debug("#################### Dump depedency tree ############################")
    for dep in deps:
      dep.dump(sys.stdout)
  install_all_libs("/home/lishijie/workspace/maven-xvsa-plugin/target/dependency", "/home/lishijie/Downloads/loc_repo", deps)


'''
This script is to used for maven plugin installation.
Please collect all maven plugins before and then run current script.
Please run the following commands for collecting dependencies:
mvn org.apache.maven.plugins:maven-dependency-plugin:3.1.0:copy-dependencies -Dmdep.addParentPoms=true -Dmdep.copyPom=true
mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.1.0:tree > target/dependency/dep_tree.txt
'''
if __name__ == "__main__":
  main("/home/xxx/workspace/maven-xvsa-plugin/target/dependency/dep_tree.txt")
