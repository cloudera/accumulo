# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os

import logging
import unittest
import time
import sys
import glob

from TestUtils import TestUtilsMixin, ACCUMULO_HOME, SITE, ROOT, ROOT_PASSWORD, INSTANCE_NAME, ZOOKEEPERS

table='testTable'
count=str(10000)
min=str(0)
max=str(99999)
valueSize=str(100)
memory=str(1<<20)
latency=str(1000)
numThreads=str(4)
visibility='A|B'
auths='A,B'

log = logging.getLogger('test.auto')

class Examples(TestUtilsMixin, unittest.TestCase):
    "Start a clean accumulo, run the examples"
    order = 21

    def runExample(self, cmd):
        self.assert_(self.wait(self.runOn(self.masterHost(), [self.accumulo_sh(),] + cmd)), "example exited with error status.")

    def ignoreExample(self, cmd):
        self.comment("  Ignoring results of command.")
        self.wait(self.runOn(self.masterHost(), [self.accumulo_sh(),] + cmd))

    def ashell(self, input):
        out, err, code = self.shell(self.masterHost(), input + '\n')
        self.assert_(code == 0)
        return out

    def comment(self, description):
        LINE = '-'*40
        log.info(LINE)
        log.info(description)
        log.info(LINE)

    def execute(self, *cmd):
        self.assert_(self.wait(self.runOn('localhost', cmd)), "command exited with error status.")

    def executeExpectFail(self, *cmd):
        self.assert_(not self.wait(self.runOn('localhost', cmd)), "command did not exit with error status and we expected it to.")

    def executeIgnoreFail(self, *cmd):
        self.wait(self.runOn('localhost', cmd))

    def runTest(self):
        self.ashell('createtable %s\nsetauths -u %s -s A,B\nquit\n' %(table, ROOT))
        examplesJar = glob.glob(ACCUMULO_HOME+'/lib/examples-simple*.jar')[0]

        self.comment("Testing dirlist example (a little)")
        self.comment("  ingesting accumulo source")
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.dirlist.Ingest',
                     INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD,
                     'dirTable',
                     'indexTable',
                     'dataTable',
                     visibility,
                     100000,
                     ACCUMULO_HOME+"/test")
        self.comment("  searching for a file")
        handle = self.runOn('localhost', [self.accumulo_sh(), 'org.apache.accumulo.examples.simple.dirlist.QueryUtil',
                                          INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD,
                                          'indexTable', auths, 'examples.py', '-search'])
        out, err = handle.communicate()
        self.assert_(handle.returncode == 0)
        self.assert_(out.find('test/system/auto/simple/examples.py') >= 0)
        self.comment("  found file at " + out)

    
        self.comment("Testing ageoff filtering")
        out = self.ashell("createtable filtertest\n"
                     "setiter -t filtertest -scan -p 10 -n myfilter -ageoff\n"
                     "\n"
                     "5000\n"
                     "\n"
                     "insert foo a b c\n"
                     "scan\n"
                     "sleep 5\n"
                     "scan\n")
        self.assert_(2 == len([line for line in out.split('\n') if line.find('foo') >= 0]))

        self.comment("Testing bloom filters are fast for missing data")
        self.ashell('createtable bloom_test\nconfig -t bloom_test -s table.bloom.enabled=true\n')
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.client.RandomBatchWriter', '-s', '7',
                     INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD, 'bloom_test',
                     '1000000', '0', '1000000000', '50', '2000000', '60000', '3', 'A')
        self.ashell('flush -t bloom_test -w\n')
        now = time.time()
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.client.RandomBatchScanner', '-s', '7',
                     INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD, 'bloom_test',
                     500, 0, 1000000000, 50, 20, 'A')
        diff = time.time() - now
        now = time.time()
        self.executeExpectFail(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.client.RandomBatchScanner', '-s', '8',
                     INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD, 'bloom_test',
                     500, 0, 1000000000, 50, 20, 'A')
        diff2 = time.time() - now
        self.assert_(diff2 < diff)

        self.comment("Creating a sharded index of the accumulo java files")
        self.ashell('createtable shard\ncreatetable doc2term\nquit\n')
        self.execute('/bin/sh', '-c',
                     'find %s/src -name "*.java" | xargs %s/bin/accumulo org.apache.accumulo.examples.simple.shard.Index %s %s shard %s %s 30' %
                     (ACCUMULO_HOME, ACCUMULO_HOME, INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD))
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.shard.Query',
                     INSTANCE_NAME, ZOOKEEPERS, 'shard', ROOT, ROOT_PASSWORD,
                     'foo', 'bar')
        self.comment("Creating a word index of the sharded files")
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.shard.Reverse',
                     INSTANCE_NAME, ZOOKEEPERS, 'shard', 'doc2term', ROOT, ROOT_PASSWORD)
        self.comment("Making 1000 conjunctive queries of 5 random words")
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.shard.ContinuousQuery',
                     INSTANCE_NAME, ZOOKEEPERS, 'shard', 'doc2term', ROOT, ROOT_PASSWORD, 5, 1000)

        self.executeIgnoreFail('hadoop', 'fs', '-rmr', "/tmp/input", "/tmp/files", "/tmp/splits.txt", "/tmp/failures")
        self.execute('hadoop', 'fs', '-mkdir', "/tmp/input")
        self.comment("Starting bulk ingest example")
        self.comment("   Creating some test data")
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.mapreduce.bulk.GenerateTestData', 0, 1000000, '/tmp/input/data')
        self.execute(self.accumulo_sh(), 'org.apache.accumulo.examples.simple.mapreduce.bulk.SetupTable',
                 INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD, 'bulkTable')
        self.execute(ACCUMULO_HOME+'/bin/tool.sh', examplesJar, 'org.apache.accumulo.examples.simple.mapreduce.bulk.BulkIngestExample',
                 INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD, 'bulkTable', '/tmp/input', '/tmp')
        self.execute(ACCUMULO_HOME+'/bin/tool.sh', examplesJar, 'org.apache.accumulo.examples.simple.mapreduce.bulk.VerifyIngest',
                 INSTANCE_NAME, ZOOKEEPERS, ROOT, ROOT_PASSWORD, 'bulkTable', 0, 1000000)
        self.wait(self.runOn(self.masterHost(), [
            'hadoop', 'fs', '-rmr', "/tmp/tableFile", "/tmp/nines"
            ]))
        self.comment("Running TeraSortIngest for a million rows")
        # 10,000 times smaller than the real terasort
        ROWS = 1000*1000
        self.wait(self.runOn(self.masterHost(), [
            ACCUMULO_HOME+'/bin/tool.sh',
            examplesJar,
            'org.apache.accumulo.examples.simple.mapreduce.TeraSortIngest',
            ROWS,  
            10, 10,
            78, 78,
            'sorted',
            INSTANCE_NAME,
            ZOOKEEPERS,
            ROOT,
            ROOT_PASSWORD,
            4]))
        self.comment("Looking for '999' in all rows")
        self.wait(self.runOn(self.masterHost(), [
            ACCUMULO_HOME+'/bin/tool.sh',
            examplesJar,
            'org.apache.accumulo.examples.simple.mapreduce.RegexExample',
            INSTANCE_NAME,
            ZOOKEEPERS,
            ROOT,
            ROOT_PASSWORD,
            'sorted',
            '.*999.*',
            '.*',
            '.*',
            '.*',
            '/tmp/nines']))
        self.comment("Generating hashes of each row into a new table")
        self.wait(self.runOn(self.masterHost(), [
            ACCUMULO_HOME+'/bin/tool.sh',
            examplesJar,
            'org.apache.accumulo.examples.simple.mapreduce.RowHash',
            INSTANCE_NAME,
            ZOOKEEPERS,
            ROOT,
            ROOT_PASSWORD,
            'sorted',
            ':',
            'sortedHashed',
            ]))
        self.comment("Exporting the table to HDFS")
        self.wait(self.runOn(self.masterHost(), [
            ACCUMULO_HOME+'/bin/tool.sh',
            examplesJar,
            'org.apache.accumulo.examples.simple.mapreduce.TableToFile',
            INSTANCE_NAME,
            ZOOKEEPERS,
            ROOT,
            ROOT_PASSWORD,
            'sortedHashed',
            ',',
            '/tmp/tableFile'
            ]))
        self.comment("Running WordCount using Accumulo aggregators")
        self.wait(self.runOn(self.masterHost(), [
            'hadoop', 'fs', '-rmr', "/tmp/wc"
            ]))
        self.wait(self.runOn(self.masterHost(), [
            'hadoop', 'fs', '-mkdir', "/tmp/wc"
            ]))
        self.wait(self.runOn(self.masterHost(), [
            'hadoop', 'fs', '-copyFromLocal', ACCUMULO_HOME + "/README", "/tmp/wc/Accumulo.README"
            ]))
        self.ashell('createtable wordCount -a count=org.apache.accumulo.core.iterators.aggregation.StringSummation\nquit\n')
        self.wait(self.runOn(self.masterHost(), [
            ACCUMULO_HOME+'/bin/tool.sh',
            examplesJar,
            'org.apache.accumulo.examples.simple.mapreduce.WordCount',
            INSTANCE_NAME,
            ZOOKEEPERS,
            '/tmp/wc',
            'wctable'
            ]))
        self.comment("Inserting data with a batch writer")
        self.runExample(['org.apache.accumulo.examples.simple.helloworld.InsertWithBatchWriter',
                        INSTANCE_NAME,
                        ZOOKEEPERS,
                        ROOT,
                        ROOT_PASSWORD,
                        'helloBatch'])
        self.comment("Reading data")
        self.runExample(['org.apache.accumulo.examples.simple.helloworld.ReadData',
                         INSTANCE_NAME,
                         ZOOKEEPERS,
                         ROOT,
                         ROOT_PASSWORD,
                         'helloBatch'])
        self.comment("Running isolated scans")
        self.runExample(['org.apache.accumulo.examples.simple.isolation.InterferenceTest',
                         INSTANCE_NAME,
                         ZOOKEEPERS,
                         ROOT,
                         ROOT_PASSWORD,
                         'itest1',
                         100000,
                         'true'])
        self.comment("Running scans without isolation")
        self.runExample(['org.apache.accumulo.examples.simple.isolation.InterferenceTest',
                         INSTANCE_NAME,
                         ZOOKEEPERS,
                         ROOT,
                         ROOT_PASSWORD,
                         'itest2',
                         100000,
                         'false'])
        self.comment("Inserting data using a map/reduce job")
        self.runExample(['org.apache.accumulo.examples.simple.helloworld.InsertWithOutputFormat',
                         INSTANCE_NAME,
                         ZOOKEEPERS,
                         ROOT,
                         ROOT_PASSWORD,
                        'helloOutputFormat'])
        self.comment("Using some example constraints")
        self.ashell('\n'.join([
            'createtable testConstraints',
            'config -t testConstraints -s table.constraint.1=org.apache.accumulo.examples.simple.constraints.NumericValueConstraint',
            'config -t testConstraints -s table.constraint.2=org.apache.accumulo.examples.simple.constraints.AlphaNumKeyConstraint',
            'insert r1 cf1 cq1 1111',
            'insert r1 cf1 cq1 ABC',
            'scan',
            'quit'
            ]))
        self.comment("Performing some row operations")
        self.runExample(['org.apache.accumulo.examples.simple.client.RowOperations',
                           INSTANCE_NAME,
                           ZOOKEEPERS,
                           ROOT,
                           ROOT_PASSWORD])
        self.comment("Using the batch writer")
        self.runExample(['org.apache.accumulo.examples.simple.client.SequentialBatchWriter',
                           INSTANCE_NAME, 
                           ZOOKEEPERS, 
                           ROOT, 
                           ROOT_PASSWORD, 
                           table,
                           min,
                           count,
                           valueSize,
                           memory,
                           latency,
                           numThreads,
                           visibility])
        self.comment("Reading and writing some data")
        self.runExample(['org.apache.accumulo.examples.simple.client.ReadWriteExample',
                           '-i', INSTANCE_NAME, 
                           '-z', ZOOKEEPERS, 
                           '-u', ROOT, 
                           '-p', ROOT_PASSWORD, 
                           '-s', auths,
                           '-t', table,
                           '-e', 
                           '-r', 
                           '-dbg'])
        self.comment("Deleting some data")
        self.runExample(['org.apache.accumulo.examples.simple.client.ReadWriteExample',
                           '-i', INSTANCE_NAME, 
                           '-z', ZOOKEEPERS, 
                           '-u', ROOT, 
                           '-p', ROOT_PASSWORD, 
                           '-s', auths,
                           '-t', table,
                           '-d', 
                           '-dbg'])
        self.comment("Writing some random data with the batch writer")
        self.runExample(['org.apache.accumulo.examples.simple.client.RandomBatchWriter',
                           '-s',
                           5,
                           INSTANCE_NAME, 
                           ZOOKEEPERS, 
                           ROOT, 
                           ROOT_PASSWORD, 
                           table,
                           count, 
                           min, 
                           max, 
                           valueSize, 
                           memory, 
                           latency, 
                           numThreads, 
                           visibility])
        self.comment("Reading some random data with the batch scanner")
        self.runExample(['org.apache.accumulo.examples.simple.client.RandomBatchScanner',
                           '-s',
                           5,
                           INSTANCE_NAME, 
                           ZOOKEEPERS, 
                           ROOT, 
                           ROOT_PASSWORD, 
                           table,
                           count, 
                           min, 
                           max, 
                           valueSize, 
                           numThreads, 
                           auths]);
        self.comment("Running an example table operation (Flush)")
        self.runExample(['org.apache.accumulo.examples.simple.client.Flush',
                           INSTANCE_NAME,
                           ZOOKEEPERS,
                           ROOT,
                           ROOT_PASSWORD,
                           table])
        self.shutdown_accumulo();


def suite():
    result = unittest.TestSuite()
    result.addTest(Examples())
    return result
