package com.redhat.it;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;

public class EJBMonitor {
	public static void main(String[] args) {
		System.setProperty("java.util.logging.config.file", "ejb-monitor-logging.properties");

		if (args.length != 1) {
			System.err.println("Usage: java ejb-monitor <configuration-file>");
			System.exit(-1);
		}
				
		Properties configuration = new Properties(); 
		try {
			configuration.load(new FileInputStream(args[0]));
		} catch (FileNotFoundException e) {
			System.err.println("Error: file not found " + e.getMessage());
			e.printStackTrace(System.err);
			System.exit(-1);
		} catch (IOException e) {
			System.err.println("Error: reading file " + e.getMessage());
			e.printStackTrace(System.err);
			System.exit(-1);
		}

		String hostname = configuration.getProperty("hostname", "localhost");
		int port = Integer.parseInt(configuration.getProperty("port", "9999"));
		String username = configuration.getProperty("username", "");
		String password = configuration.getProperty("password", "");
		String realm = configuration.getProperty("realm", "");
		String regexpHostname = configuration.getProperty("regexpHostname", ".*");
		String regexpServer = configuration.getProperty("regexpServer", ".*");
		String appsProperty= configuration.getProperty("apps", "");		
		List<String> apps = Arrays.asList(appsProperty.split(","));
				
		Pattern patternHostname = Pattern.compile(regexpHostname);
		Pattern patternServer = Pattern.compile(regexpServer);

		System.out.println("date,host,server,deploy,subdeploy,ejb,method,invocations,execution-time,wait-time");
		
		ModelControllerClient client = null;
		try {
			client = connect(hostname, port, username, password, realm);
			
	        Set<String> hosts = getHosts(client);
	        
	        for (String host : hosts) {
	        	if (patternHostname.matcher(host).matches()) {
	        		Set<String> servers = getServers(client, host);
	        		for (String server : servers) {
	        			if (patternServer.matcher(server).matches()) {
	        				Set<String> deployments = getDeployments(client, host, server);
	        				for (String deployment : deployments) {
	        					if (apps.contains(deployment.substring(0, deployment.lastIndexOf('-')))) {
	                				ModelNode statistics = getAllStatistics(client, host, server, deployment);
	                				printStatistics(statistics, host, server, deployment);
	        					}
	        				}
	        			}
	        		}
	        	}
	        }
		} catch (UnknownHostException e) {
			System.err.println("Error: unknown hostname " + e.getMessage());
			e.printStackTrace(System.err);
		} catch (IOException e) {
			System.err.println("Error: i/o error " + e.getMessage());
			e.printStackTrace(System.err);
		} finally {
			if (client != null) {
				try {
					disconnect(client);
				} catch (IOException e) {
					System.err.println("Error: i/o error " + e.getMessage());
					e.printStackTrace(System.err);					
				}
			}
		}
	}

	private static ModelControllerClient connect(final String host, final int port, final String username, final String password, final String securityRealmName) throws UnknownHostException {
		final CallbackHandler callbackHandler = new CallbackHandler() {	
			public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
				for (Callback current : callbacks) {
					if (current instanceof NameCallback) {
						NameCallback ncb = (NameCallback) current;
						ncb.setName(username);
					} else if (current instanceof PasswordCallback) {
						PasswordCallback pcb = (PasswordCallback) current;
						pcb.setPassword(password.toCharArray());
					} else if (current instanceof RealmCallback) {
						RealmCallback rcb = (RealmCallback) current;
						rcb.setText(rcb.getDefaultText());
					} else {
						throw new UnsupportedCallbackException(current);
					}
				}
			}
		};

		return ModelControllerClient.Factory.create(host, port, callbackHandler);
	}
	
		
	private static void disconnect(ModelControllerClient client) throws IOException  {
		client.close();
	}

	private static Set<String> getHosts(ModelControllerClient client) throws IOException {
		ModelNode op = new ModelNode();
        op.get("operation").set("read-resource");
        op.get("operations").set(true);
        ModelNode response = client.execute(op);

        return response.get("result").get("host").keys();
	}

	
	private static Set<String> getServers(ModelControllerClient client, String host) throws IOException {
		ModelNode op = new ModelNode();
        op.get("operation").set("read-resource");
        op.get("operations").set(true);
        ModelNode address = op.get("address");
        address.add("host", host);
        
        ModelNode response = client.execute(op);

        return response.get("result").get("server").keys();
	}
		
	private static Set<String> getDeployments(ModelControllerClient client, String host, String server) throws IOException {
		ModelNode op = new ModelNode();
        op.get("operation").set("read-resource");
        op.get("operations").set(true);
        ModelNode address = op.get("address");
        address.add("host", host);
        address.add("server", server);
        
        ModelNode response = client.execute(op);

        return response.get("result").get("deployment").keys();
	}

	
	private static ModelNode getAllStatistics(ModelControllerClient client, String host, String server, String deployment) throws IOException {
		ModelNode op = new ModelNode();
		op.get("operation").set("read-resource");
		op.get("operations").set(true);
		op.get("include-runtime").set(true);
		op.get("recursive").set(true);
		ModelNode address = op.get("address");
		address.add("host", host);
		address.add("server", server);
		address.add("deployment", deployment);
    
		return client.execute(op);
	}

	private static void printStatistics(ModelNode response, String host, String server, String deploy) {
	    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
	    String now = dateFormat.format(new Date());
		
		for (String subdeployment : response.get("result").get("subdeployment").keys()) {
			if (response.get("result").get("subdeployment").get(subdeployment).get("subsystem").has("ejb3")) {
				for (String stateful : response.get("result").get("subdeployment").get(subdeployment).get("subsystem").get("ejb3").get("stateful-session-bean").keys()) {
					for (String method : response.get("result").get("subdeployment").get(subdeployment).get("subsystem").get("ejb3").get("stateful-session-bean").get(stateful).get("methods").keys()) {
						ModelNode methodNode = response.get("result").get("subdeployment").get(subdeployment).get("subsystem").get("ejb3").get("stateful-session-bean").get(stateful).get("methods").get(method);
						
						System.out.print(now + ",");
						System.out.print(host + ",");
						System.out.print(server + ",");
						System.out.print(deploy + ",");
						System.out.print(subdeployment + ",");
						System.out.print(stateful + ",");
						System.out.print(method + ",");
						System.out.print(methodNode.get("invocations").asLong() + ",");
						System.out.print(methodNode.get("execution-time").asLong() + ",");
						System.out.print(methodNode.get("wait-time").asLong());
						System.out.println();
					}
				}
			}
		}
	}
}
