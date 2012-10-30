# Copyright (c) 2009-2011 by Minor Gordon, Bjoern Kolbeck, Zuse Institute Berlin
# Licensed under the BSD License, see LICENSE file for details.

from datetime import datetime
from time import sleep
import sys, os, subprocess, signal

class Server:
    def __init__(self,
                 start_stop_retries,
                 config_file_path,
                 run_dir_path,
                 xtreemfs_dir,
                 data_dir,
                 rpc_port,
                 uuid):
        self._start_stop_retries = start_stop_retries
        self._config_file_path = config_file_path
        self._run_dir_path = run_dir_path
        self._xtreemfs_dir = xtreemfs_dir
        self._data_dir = data_dir
        self._config = dict()
        # Initialize with default values
        self._config['listen.port'] = rpc_port
        self._config['http_port'] = rpc_port - 2000
        self._config['debug.level'] = 6
        self._config['uuid'] = uuid
        self._config['ssl.enabled'] = 'false'

    def configure(self):
        pass
        # Nothing to do here.

    def set_debug_level(self, debug_level):
        self._config['debug.level'] = debug_level

    def enable_ssl(self,
                   use_gridssl,
                   pkcs12_file_path,
                   pkcs12_passphrase,
                   trusted_certs_jks_file_path,
                   trusted_certs_jks_passphrase):
        self._config['ssl.enabled'] = 'true'
        if use_gridssl:
            self._config['ssl.grid_ssl'] = 'true'
        else:
            self._config['ssl.grid_ssl'] = 'false'

        self._config['ssl.service_creds'] = pkcs12_file_path
        self._config['ssl.service_creds.pw'] = pkcs12_passphrase
        self._config['ssl.service_creds.container'] = 'PKCS12'
        self._config['ssl.trusted_certs'] = trusted_certs_jks_file_path
        self._config['ssl.trusted_certs.pw'] = trusted_certs_jks_passphrase
        self._config['ssl.trusted_certs.container'] = 'JKS'

    # set configuration parameters required for SNMP support
    def enable_snmp(self,
                    snmp_port,
                    snmp_address,
                    snmp_aclfile):
        self._config['snmp.enabled'] = 'true'
        self._config['snmp.port'] = snmp_port
        self._config['snmp.address'] = snmp_address
        self._config['snmp.aclfile'] = snmp_aclfile
        
         
    # Imports the configuration from the config file.
    def read_config_file(self):
        self._config = dict()
        for line in open(self._config_file_path).readlines():
            line_parts = line.split( "=", 1 )
            if len( line_parts ) == 2:
                self._config[line_parts[0].strip()] = line_parts[1].strip()

    # Writes the current configuration to a config file
    def write_config_file(self):
        text = "# autogenerated by test_server.py at "+str(datetime.now())+"\n"
        for k in sorted(self._config.keys()):
            text += str(k) +  "=" + str(self._config[k]) + "\n"
        f = open(self._config_file_path,'w')
        f.write(text)
        f.close()

    def get_config_file_path(self):
        return self._config_file_path

    def _get_config_property(self, key):
        return self._config[key]

    def _get_pid_file_path(self):
        return os.path.join(self._run_dir_path, self.get_uuid() + ".pid")

    def get_http_port(self):
        return int(self._config["http_port"])

    def get_rpc_port(self):
        return int(self._config["listen.port"])

    def get_uuid(self):
        return self._config["uuid"]

    def getServiceUrl(self):
        url = "pbrpc://"
        if (self._config['ssl.enabled'] == 'true'):
            if (self._config['ssl.grid_ssl'] == 'true'):
                url = "pbrpcg://"
            else:
                url = "pbrpcs://"
        url += "localhost:" + str(self._config["listen.port"]) + "/"
        return url

    def is_running(self):
        pid_file_path = self._get_pid_file_path()
        if os.path.exists(pid_file_path):
            pid = open(pid_file_path).read().strip()

            try:
                pid = int(pid)
            except ValueError:
                return False

            #print "xtestenv: checking if", self.__class__.__name__, "server is running with pid", pid

            try:
                pid, exitvalue = os.waitpid(int(pid), os.WNOHANG)
                if pid != 0 and exitvalue != 0:
                    return False
                else:
                    return True
            except OSError:
                return False
        else:
            return False

    def save_status_page(self, to_file_path):
        http_port = self.get_http_port()
        os.system("wget -O %(to_file_path)s http://localhost:%(http_port)u" % locals())

    def start(self,
              log_file_path=None):

        if sys.platform == "win32" or not self.is_running():
            try: os.mkdir(self._run_dir_path)
            except: pass
            pid_file_path = self._get_pid_file_path()

            java_args = [os.path.join(os.environ["JAVA_HOME"], "bin", "java")]

            # Enable assertions.
            java_args.append("-ea")

            # Construct the -cp classpath
            XtreemFS_jar_file_path = os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "servers", "dist", "XtreemFS.jar"))
            if os.path.exists(XtreemFS_jar_file_path):
                classpath = (
                             XtreemFS_jar_file_path,
                             os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "lib", "BabuDB.jar")),
                             os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "lib", "protobuf-java-2.3.0.jar")),
                             os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "flease", "dist", "Flease.jar")),
                             os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "foundation", "dist", "Foundation.jar")),
                             os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "lib", "jdmkrt.jar")),
                             os.path.abspath(os.path.join(self._xtreemfs_dir, "java", "lib", "commons-codec-1.3.jar")),
                             )
                if sys.platform.startswith("win"):
                    classpath = ";".join(classpath)
                else:
                    classpath = ":".join(classpath)
                java_args.extend(("-cp", classpath))

            # Name of the class to start
            java_args.append("org.xtreemfs." + self.__class__.__name__.lower() + "." + self.__class__.__name__.upper())

            # .config file
            java_args.append(self.get_config_file_path())

            # Don't .join java_args, since Popen wants a sequence when shell=False

            if log_file_path is None:
                stderr = sys.stderr
                stdout = sys.stdout
            else:
                # Redirect stderr and stdout to a log file
                stderr = stdout = open(log_file_path, "a")

            #print "xctl: starting", self.__class__.__name__, "server with UUID", self.get_uuid(), "on port", self.get_rpc_port(), "with", " ".join(java_args)

            p = subprocess.Popen(java_args, stdout=stdout, stderr=stderr) # No shell=True: we only want one process (java), not two (/bin/sh and java)
            if p.returncode is not None:
                raise RuntimeError(self.get_uuid() + " failed to start: " + str(p.returncode))
            pidfile = open(pid_file_path, "w+")
            pidfile.write(str(p.pid))
            pidfile.close()

            print "xtestenv: started", self.__class__.__name__, "server with UUID", self.get_uuid(), "on port", self.get_rpc_port(), "with pid", p.pid

            sleep(2.0)

            if not self.is_running():
                raise RuntimeError, self.get_uuid() + " failed to start"
        else:
            print "xtestenv:", self.__class__.__name__, "server with UUID", self.get_uuid(), "is already running"

    def stop(self):
        pid_file_path = self._get_pid_file_path()
        if os.path.exists(pid_file_path):
            pid = int(open(pid_file_path).read().strip())

            if sys.platform.startswith("win"):
                subprocess.call("TASKKILL /PID %(pid)u /F /T" % locals())
                killed = True
            else:
                killed = False
                for signo in (signal.SIGTERM, signal.SIGKILL):
                    for try_i in xrange(self._start_stop_retries):
                        print "xtestenv: stopping", self.__class__.__name__, "server with pid", pid, "with signal", str(signo) + ", try", try_i

                        try: os.kill(pid, signo)
                        except: pass

                        sleep(1)

                        try:
                            if os.waitpid(pid, os.WNOHANG)[0] != 0:
                                killed = True
                                break
                        except OSError:
                            killed = True
                            break
                        except:
                            if DEBUG_ME:
                                traceback.print_exc()

                    if killed:
                        break

            if killed:
                os.unlink(pid_file_path)

        else:
            print "xtestenv: no pid file for", self.__class__.__name__, "server"


class DIR(Server):
    def configure(self):
        try: os.mkdir(self._data_dir)
        except: pass

        self._config['babudb.debug.level'] = self._config['debug.level']
        self._config['babudb.logDir'] = self._data_dir
        self._config['babudb.baseDir'] = self._data_dir
        self._config['babudb.sync'] = 'FSYNC'
        self._config['babudb.worker.maxQueueLength'] = '250'
        self._config['babudb.worker.numThreads'] = '0'
        self._config['babudb.maxLogfileSize'] = '16777216'
        self._config['babudb.checkInterval'] = '300'
        self._config['babudb.pseudoSyncWait'] = '200'
        self._config['database.dir'] = self._data_dir
        self._config['database.log'] = self._data_dir
        self._config['authentication_provider'] = 'org.xtreemfs.common.auth.NullAuthProvider'


class MRC(Server):
    def configure(self,
                  dir_host,
                  dir_port):
        try: os.mkdir(self._data_dir)
        except: pass

        self._config['dir_service.host'] = dir_host
        self._config['dir_service.port'] = dir_port

        self._config['osd_check_interval'] = 300
        self._config['no_atime'] = 'true'
        self._config['no_fsync'] = 'true'
        self._config['local_clock_renewal'] = 0
        self._config['remote_time_sync'] = 60000
        self._config['capability_secret'] = 'testsecret'
        self._config['database.checkpoint.interval'] = 1800000
        self._config['database.checkpoint.idle_interval'] = 1000
        self._config['database.checkpoint.logfile_size'] = 16384

        self._config['babudb.debug.level'] = self._config['debug.level']
        self._config['babudb.logDir'] = self._data_dir
        self._config['babudb.baseDir'] = self._data_dir
        self._config['babudb.sync'] = 'ASYNC'
        self._config['babudb.worker.maxQueueLength'] = '250'
        self._config['babudb.worker.numThreads'] = '0'
        self._config['babudb.maxLogfileSize'] = '16777216'
        self._config['babudb.checkInterval'] = '300'
        self._config['babudb.pseudoSyncWait'] = '0'
        self._config['database.dir'] = self._data_dir
        self._config['database.log'] = self._data_dir
        self._config['authentication_provider'] = 'org.xtreemfs.common.auth.NullAuthProvider'


class OSD(Server):
    def configure(self,
                  dir_host,
                  dir_port):
        try: os.mkdir(self._data_dir)
        except: pass

        self._config['dir_service.host'] = dir_host
        self._config['dir_service.port'] = dir_port

        self._config['local_clock_renewal'] = 0
        self._config['remote_time_sync'] = 60000
        self._config['capability_secret'] = 'testsecret'
        self._config['report_free_space'] = 'true'
        self._config['checksums.enabled'] = 'false'

        self._config['object_dir'] = self._data_dir

        # Some tests overload the test system, increase timeouts.
        self._config['flease.lease_timeout_ms'] = 60000
        self._config['flease.message_to_ms'] = 2000
