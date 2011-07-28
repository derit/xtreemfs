/*
 * Copyright (c) 2008-2010 by Jan Stender, Bjoern Kolbeck,
 *               Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.xtreemfs.common.config.ServiceConfig;
import org.xtreemfs.common.util.NetUtils;
import org.xtreemfs.common.uuids.ServiceUUID;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.pbrpc.Schemes;
import org.xtreemfs.foundation.pbrpc.client.PBRPCException;
import org.xtreemfs.foundation.pbrpc.client.RPCResponse;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.Auth;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.AuthType;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR;
import org.xtreemfs.pbrpc.generatedinterfaces.DIRServiceClient;
import org.xtreemfs.pbrpc.generatedinterfaces.Common.emptyResponse;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.AddressMapping;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.AddressMappingSet;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.Configuration;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.Service;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceDataMap;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceSet;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceType;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.configurationSetResponse;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.serviceRegisterResponse;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.KeyValuePair;

/**
 * A thread that regularly sends a heartbeat signal with fresh service data to
 * the Directory Service.
 */
public class HeartbeatThread extends LifeCycleThread {

    /**
     * @return the advertisedHostName
     */
    public String getAdvertisedHostName() {
        return advertisedHostName;
    }

    /**
     * An interface that generates service data to be sent to the Directory
     * Service. Each time a heartbeat signal is sent, new service data will be
     * generated by means of invoking <tt>getServiceData()</tt>.
     */
    public interface ServiceDataGenerator {

        public DIR.ServiceSet getServiceData();
    }

    private static final long UPDATE_INTERVAL = 60 * 1000; // 60s

    private ServiceUUID uuid;

    private ServiceDataGenerator serviceDataGen;

    private DIRServiceClient client;

    private volatile boolean quit;

    private final ServiceConfig config;

    private final boolean advertiseUDPEndpoints;

    private final String proto;

    private String advertisedHostName;

    private final UserCredentials uc;

    public static final String STATIC_ATTR_PREFIX = "static.";

    public static final String STATUS_ATTR = STATIC_ATTR_PREFIX+"status";


    private static Auth     authNone;

    static {
        authNone = Auth.newBuilder().setAuthType(AuthType.AUTH_NONE).build();
    }

    public HeartbeatThread(String name, DIRServiceClient client, ServiceUUID uuid,
            ServiceDataGenerator serviceDataGen, ServiceConfig config, boolean advertiseUDPEndpoints) {

        super(name);

        setPriority(Thread.MAX_PRIORITY);

        this.client = client;
        this.uuid = uuid;
        this.serviceDataGen = serviceDataGen;
        this.config = config;
        this.advertiseUDPEndpoints = advertiseUDPEndpoints;
        this.uc = UserCredentials.newBuilder().setUsername("hb-thread").addGroups("xtreemfs-services").build();
        if (!config.isUsingSSL()) {
            proto = Schemes.SCHEME_PBRPC;
        } else {
            if (config.isGRIDSSLmode()) {
                proto = Schemes.SCHEME_PBRPCG;
            } else {
                proto = Schemes.SCHEME_PBRPCS;
            }
        }
    }

    public synchronized void shutdown() {
        RPCResponse<emptyResponse> r = null;
        try {
            if (client.clientIsAlive()) {

                RPCResponse r1 = client.xtreemfs_service_deregister(null, authNone,uc,uuid.toString());
                r1.get();
                r1.freeBuffers();

            }
        } catch (Exception ex) {
            Logging.logMessage(Logging.LEVEL_WARN, this, "could not deregister service at DIR");
            Logging.logError(Logging.LEVEL_WARN, this, ex);
        } finally {
            try {
                if (r != null) {
                    r.freeBuffers();
                }
            } catch (Throwable thr) {
            }
        }

        this.quit = true;
        this.interrupt();
    }

    public void initialize() throws IOException {

        List<RPCResponse> responses = new LinkedList<RPCResponse>();

        // initially, ...
        try {

            // ... for each UUID, ...
            registerServices();

            // ... register the address mapping for the service

            //AddressMappingSet endpoints = null;
            List<AddressMapping.Builder> endpoints = null;

            // check if hostname or listen.address are set
            if ("".equals(config.getHostName()) && config.getAddress() == null) {

                endpoints = NetUtils.getReachableEndpoints(config.getPort(),
                        proto);

                if (endpoints.size() > 0)
                    advertisedHostName = endpoints.get(0).getAddress();

                if (advertiseUDPEndpoints) {
                    endpoints.addAll(NetUtils.getReachableEndpoints(config.getPort(),
                            Schemes.SCHEME_PBRPCU));
                }

                for (AddressMapping.Builder endpoint : endpoints) {
                    endpoint.setUuid(uuid.toString());
                }

            } else {
                // if it is set, we should use that for UUID mapping!
                endpoints = new ArrayList(10);

                // remove the leading '/' if necessary
                String host = "".equals(config.getHostName()) ? config.getAddress().getHostName() : config.getHostName();
                if (host.startsWith("/")) {
                    host = host.substring(1);
                }

                try {
                    //see if we can resolve the hostname
                    InetAddress ia = InetAddress.getByName(host);
                } catch (Exception ex) {
                    Logging.logMessage(Logging.LEVEL_WARN, this, "WARNING! Could not resolve my " +
                            "hostname (%s) locally! Please make sure that the hostname is set correctly " +
                            "(either on your system or in the service config file). This will lead to " +
                            "problems if clients and other OSDs cannot resolve this service's address!\n", host);
                }

                AddressMapping.Builder tmp = AddressMapping.newBuilder().setUuid(uuid.toString()).setVersion(0).setProtocol(proto).setAddress(host).setPort(config.getPort()).setMatchNetwork("*").setTtlS(3600).setUri( proto + "://" + host + ":" + config.getPort());
                endpoints.add(tmp);
                // add an oncrpc/oncrpcs mapping

                /*endpoints.add(new AddressMapping(uuid.toString(), 0, proto, host, config.getPort(), "*", 3600,
                        proto + "://" + host + ":" + config.getPort()));*/

                advertisedHostName = host;

                if (advertiseUDPEndpoints) {
                    /*endpoints.add(new AddressMapping(uuid.toString(), 0, XDRUtils.ONCRPCU_SCHEME, host,
                            config.getPort(), "*", 3600, XDRUtils.ONCRPCU_SCHEME + "://" + host + ":" + config.getPort()));*/

                    tmp = AddressMapping.newBuilder().setUuid(uuid.toString()).setVersion(0).setProtocol(Schemes.SCHEME_PBRPCU)
                            .setAddress(host).setPort(config.getPort()).setMatchNetwork("*").setTtlS(3600).setUri( Schemes.SCHEME_PBRPCU + "://" + host + ":" + config.getPort());
                    endpoints.add(tmp);
                }

            }

            if (Logging.isInfo()) {
                Logging.logMessage(Logging.LEVEL_INFO, Category.net, this,
                        "registering the following address mapping for the service:");
                for (AddressMapping.Builder mapping : endpoints) {
                    Logging.logMessage(Logging.LEVEL_INFO, Category.net, this, mapping.toString());
                }
            }

            // fetch the latest address mapping version from the Directory
            // Serivce
            long version = 0;
            RPCResponse<AddressMappingSet> r2 = client.xtreemfs_address_mappings_get(null, authNone, uc, uuid.toString());
            try {
                AddressMappingSet ams = r2.get();

                // retrieve the version number from the address mapping
                if (ams.getMappingsCount() > 0) {
                    version = ams.getMappings(0).getVersion();
                }
            } finally {
                responses.add(r2);
            }

            if (endpoints.size() > 0) {
                endpoints.get(0).setVersion(version);
            }

            AddressMappingSet.Builder ams = AddressMappingSet.newBuilder();
            for (AddressMapping.Builder mapping : endpoints) {
                ams.addMappings(mapping);
            }
            // register/update the current address mapping
            RPCResponse r3 = client.xtreemfs_address_mappings_set(null, authNone, uc, ams.build());
            try {
                r3.get();
            } finally {
                responses.add(r3);
            }
        } catch (InterruptedException ex) {
        } catch (Exception ex) {
            Logging.logMessage(Logging.LEVEL_ERROR, this,
                    "an error occurred while initially contacting the Directory Service: " + ex);
            throw new IOException("cannot initialize service at XtreemFS DIR: " + ex, ex);
        } finally {
            for (RPCResponse resp : responses) {
                resp.freeBuffers();
            }
        }
        
        try {
			this.setServiceConfiguration();
		} catch (Exception e) {
			// TODO: handle exception
		}
    }

    public void run() {
        try {

            Map<String, Long> verMap = new HashMap<String, Long>();

            notifyStarted();

            // periodically, ...
            while (!quit) {

                synchronized (this) {


                    try {

                        registerServices();

                    } catch (IOException ex) {
                        Logging.logError(Logging.LEVEL_ERROR, this, ex);
                    } catch (InterruptedException ex) {
                        quit = true;
                        break;
                    }

                    if (quit) {
                        break;
                    }

                }

                try {
                    Thread.sleep(UPDATE_INTERVAL);
                } catch (InterruptedException e) {
                    // ignore
                }
            }

            notifyStopped();
        } catch (Exception ex) {
            notifyCrashed(ex);
        }
    }

    private void registerServices() throws IOException, PBRPCException, InterruptedException {
        
        for (Service reg : serviceDataGen.getServiceData().getServicesList()) {

            RPCResponse<ServiceSet> r1 = null;
            RPCResponse<serviceRegisterResponse> r2 = null;
            try {

                // retrieve old DIR entry
                r1 = client.xtreemfs_service_get_by_uuid(null, authNone, uc, reg.getUuid());
                long currentVersion = 0;
                ServiceSet oldSet = r1.get();
                Service oldService = oldSet.getServicesCount() == 0? null: oldSet.getServices(0);
                
                Map<String,String> staticAttrs = new HashMap();
                if (oldService != null) {
                    currentVersion = oldService.getVersion();
                    final ServiceDataMap data = oldService.getData();
                    for (KeyValuePair pair : data.getDataList()) {
                        if (pair.getKey().startsWith(STATIC_ATTR_PREFIX))
                            staticAttrs.put(pair.getKey(),pair.getValue());
                    }
                }

                if (!staticAttrs.containsKey(STATUS_ATTR))
                    staticAttrs.put(STATUS_ATTR, Integer.toString(DIR.ServiceStatus.SERVICE_STATUS_AVAIL.getNumber()));

                Service.Builder builder = reg.toBuilder();
                builder.setVersion(currentVersion);
                final ServiceDataMap.Builder data = ServiceDataMap.newBuilder();
                for (Entry<String,String> sAttr : staticAttrs.entrySet()) {
                    data.addData(KeyValuePair.newBuilder().setKey(sAttr.getKey()).setValue(sAttr.getValue()).build());
                }
                
                // If the service to register is a volume, and a volume with the
                // same ID but a different MRC has been registered already, it
                // may be necessary to register the volume's MRC as a replica.
                // In this case, all keys starting with 'mrc' have to be treated
                // separately.
                if (reg.getType() == ServiceType.SERVICE_TYPE_VOLUME && oldService != null
                    && oldService.getUuid().equals(reg.getUuid())) {
                    
                    // retrieve the MRC UUID attached to the volume to be
                    // registered
                    String mrcUUID = null;
                    for (KeyValuePair kv : reg.getData().getDataList())
                        if (kv.getKey().equals("mrc")) {
                            mrcUUID = kv.getValue();
                            break;
                        }
                    assert (mrcUUID != null);
                    
                    // check if the UUID is already contained in the volume's
                    // list of MRCs and determine the next vacant key
                    int maxMRCNo = 1;
                    boolean contained = false;
                    for (KeyValuePair kv : oldService.getData().getDataList()) {
                        
                        if (kv.getKey().startsWith("mrc")) {
                            
                            data.addData(kv);
                            
                            if (kv.getValue().equals(mrcUUID))
                                contained = true;
                            
                            if (!kv.getKey().equals("mrc")) {
                                int no = Integer.parseInt(kv.getKey().substring(3));
                                if (no > maxMRCNo)
                                    maxMRCNo = no;
                            }
                        }
                    }
                    
                    // if the UUID is not contained, add it
                    if (!contained)
                        data.addData(KeyValuePair.newBuilder().setKey("mrc" + (maxMRCNo + 1)).setValue(
                            mrcUUID));
                    
                    // add all other key-value pairs
                    for(KeyValuePair kv: reg.getData().getDataList())
                        if(!kv.getKey().startsWith("mrc"))
                            data.addData(kv);
                    
                }

                // in any other case, all data can be updated
                else
                    data.addAllData(reg.getData().getDataList());
                
                builder.setData(data);
                r2 = client.xtreemfs_service_register(null, authNone, uc, builder.build());
                r2.get();

                if (Logging.isDebug()) {
                    Logging.logMessage(Logging.LEVEL_DEBUG, Category.misc, this,
                            "%s successfully updated at Directory Service", uuid);
                }
            } finally {
                if (r1 != null) {
                    r1.freeBuffers();
                }
                if (r2 != null) {
                    r2.freeBuffers();
                }
            }
        }
    }

   
    private void setServiceConfiguration() throws IOException, PBRPCException, InterruptedException {
        
        RPCResponse<Configuration> responseGet = null;
        RPCResponse<configurationSetResponse> responseSet = null;
             
        
        try {
            
            responseGet = client.xtreemfs_configuration_get(null, authNone, uc, uuid.toString());
            long currentVersion = 0;
            
            Configuration conf = responseGet.get();
            currentVersion = conf.getVersion();
            
            Configuration.Builder confBuilder = Configuration.newBuilder();
            confBuilder.setUuid(uuid.toString()).setVersion(currentVersion);
            for (Map.Entry<String, String> mapEntry : config.toHashMap().entrySet()) {
                confBuilder.addParameter(
                        KeyValuePair.newBuilder().setKey(mapEntry.getKey())
                        .setValue(mapEntry.getValue()).build()
                        );
            }
                              
            responseSet = client.xtreemfs_configuration_set(null, authNone, uc, confBuilder.build());
            responseSet.get();
            
            
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.misc, this,
                        "%s successfully send configuration to Directory Service", uuid);
            }
            
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            if (responseGet != null) {
                responseGet.freeBuffers();
            }
            if (responseSet != null) {
                responseSet.freeBuffers();
            }

        }
      }

    
    public static void waitForDIR(InetSocketAddress dirAddress, int maxWait_s) throws IOException {
        //check if we can connect to DIR and wait if necessary
        final long tStart = System.currentTimeMillis();
        final long maxWait = maxWait_s * 1000;
        int wait = 1;

        do {

            try {
                Socket s = new Socket();
                s.connect(dirAddress, 2000);
                s.close();
                break;
            } catch (UnknownHostException ex) {
                throw new IOException("Initialization failed: " + ex);
            } catch (IOException ex) {
                //wait for next try
                Logging.logMessage(Logging.LEVEL_WARN, null, "cannot connect to DIR (" + ex + "), waiting " + wait + "s");
            } catch (Exception ex) {
                //abort
                throw new IOException("Initialization failed: " + ex);
            }
            long now = System.currentTimeMillis();
            if (now >= tStart + maxWait) {
                throw new IOException("Initialization failed: XtreemFS DIR @ " + dirAddress + " does not respond.");
            }
            try {
                Thread.sleep(wait * 1000);
            } catch (InterruptedException ex) {
                throw new IOException("Initialization failed: " + ex);
            }
            wait += 1;
        } while (true);
        Logging.logMessage(Logging.LEVEL_INFO, null, "XtreemFS DIR @ " + dirAddress + " ok");
    }
}