/*
* Copyright (c) 2014 General Electric Company. All rights reserved.
*
* The copyright to the computer software herein is the property of
* General Electric Company. The software may be used and/or copied only
* with the written permission of General Electric Company or in accordance
* with the terms and conditions stipulated in the agreement/contract
* under which the software has been supplied.
*/

package com.ge.predix.solsvc.simulator.adapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ge.dspmicro.hoover.api.spillway.ISpillway;
import com.ge.dspmicro.machinegateway.api.adapter.IDataSubscription;
import com.ge.dspmicro.machinegateway.api.adapter.IDataSubscriptionListener;
import com.ge.dspmicro.machinegateway.api.adapter.IEdgeDataSubscription;
import com.ge.dspmicro.machinegateway.api.adapter.IMachineAdapter;
import com.ge.dspmicro.machinegateway.api.adapter.ISubscriptionAdapterListener;
import com.ge.dspmicro.machinegateway.api.adapter.ISubscriptionMachineAdapter;
import com.ge.dspmicro.machinegateway.api.adapter.MachineAdapterException;
import com.ge.dspmicro.machinegateway.api.adapter.MachineAdapterInfo;
import com.ge.dspmicro.machinegateway.api.adapter.MachineAdapterState;
import com.ge.dspmicro.machinegateway.types.PDataNode;
import com.ge.dspmicro.machinegateway.types.PDataValue;
import com.ge.dspmicro.machinegateway.types.PEnvelope;
import com.ge.predix.solsvc.simulator.api.ISampleAdapterConfig;
import com.ge.predix.solsvc.simulator.config.JsonDataNode;
import com.ge.predix.solsvc.simulator.rest.IHttpClientSampleRestServer;
import com.ge.predix.solsvc.simulator.types.DataSimulatorResponse;
import com.ge.predix.solsvc.simulator.types.SampleDataNode;
import com.ge.predix.solsvc.simulator.types.SampleDataSubscription;
import com.ge.predix.solsvc.simulator.types.SampleSubscriptionListener;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Modified;
import aQute.bnd.annotation.component.Reference;
import parsii.eval.Expression;
import parsii.eval.Parser;
import parsii.tokenizer.ParseException;

/**
 *
 * @author Predix Machine Subscription Adapter to generate sample data as configured in com.ge.predix.workshop.nodeconfig.json 
 */
@Component(name = SampleMachineAdapter.SERVICE_PID, provide =
{
		ISubscriptionMachineAdapter.class, IMachineAdapter.class
})
@Path(IHttpClientSampleRestServer.PATH)
public class SampleMachineAdapter
implements ISubscriptionMachineAdapter,IHttpClientSampleRestServer
{


	/** Service PID for Sample Machine Adapter */
	public static final String SERVICE_PID = "com.ge.predix.solsvc.simulator.adapter"; //$NON-NLS-1$
	/**
	 * The regular expression used to split property values into String array.
	 */
	public final static String SPLIT_PATTERN = "\\s*\\|\\s*"; //$NON-NLS-1$

	public final static String MACHINE_HOME = System.getProperty("predix.home.dir"); //$NON-NLS-1$
	// Create logger to report errors, warning massages, and info messages
	// (runtime Statistics)
	private static final Logger _logger = LoggerFactory.getLogger(SampleMachineAdapter.class);
	private UUID uuid = UUID.randomUUID();
	private MachineAdapterInfo adapterInfo;
	private MachineAdapterState adapterState;
	
	private Map<UUID, SampleDataNode> dataNodes = new HashMap<UUID, SampleDataNode>();

	private ISampleAdapterConfig config;
	/**
	 * Data cache for holding latest data updates
	 */
	protected Map<UUID, PDataValue> dataValueCache = new ConcurrentHashMap<UUID, PDataValue>();
	private Map<UUID, SampleDataSubscription> dataSubscriptions = new HashMap<UUID, SampleDataSubscription>();

	private List<JsonDataNode> configNodes = new ArrayList<JsonDataNode>();
	private IDataSubscriptionListener dataUpdateHandler = new SampleSubscriptionListener();

	private DecimalFormat decimalFormat = new DecimalFormat("####.##"); //$NON-NLS-1$

	private ISpillway spillway;
	/*
	 * ############################################### # OSGi service lifecycle
	 * management # ###############################################
	 */

	/**
	 * OSGi component lifecycle activation method
	 *
	 * @param ctx
	 *            component context
	 * @throws IOException
	 *             on fail to load/set configuration properties
	 */
	@Activate
	public void activate(ComponentContext ctx) throws IOException {
		_logger.info("Starting sample " + ctx.getBundleContext().getBundle().getSymbolicName()); //$NON-NLS-1$

		ObjectMapper mapper = new ObjectMapper();
		File configFile = new File(MACHINE_HOME + File.separator + this.config.getNodeConfigFile());
		this.configNodes = mapper.readValue(configFile, new TypeReference<List<JsonDataNode>>() {
			//
		});
		createNodes(this.configNodes);

		this.adapterInfo = new MachineAdapterInfo(this.config.getAdapterName(), SampleMachineAdapter.SERVICE_PID,
				this.config.getAdapterDescription(), ctx.getBundleContext().getBundle().getVersion().toString());

		List<String> subs = Arrays.asList(parseDataSubscriptions());
		// Start data subscription and sign up for data updates.
		for (String sub : subs) {
			SampleDataSubscription dataSubscription = new SampleDataSubscription(this, sub, this.config.getUpdateInterval(),
					new ArrayList<PDataNode>(this.dataNodes.values()),spillway);
			this.dataSubscriptions.put(dataSubscription.getId(), dataSubscription);
			// Using internal listener, but these subscriptions can be used with
			// Spillway listener also
			dataSubscription.addDataSubscriptionListener(this.dataUpdateHandler);
			new Thread(dataSubscription).start();
		}
	}

	/*
	 * parse the Data Subscriptions
	 */
	private String[] parseDataSubscriptions() {

		Object objectValue = this.config.getDataSubscriptions();
		_logger.info("Data Subscriptions :  " + objectValue); //$NON-NLS-1$ //$NON-NLS-2$
		if (objectValue == null) {
			invalidDataSubscription();
		} else {

			if (objectValue instanceof String[]) {
				if (((String[]) objectValue).length == 0) {
					invalidDataSubscription();
				}
				return (String[]) objectValue;
			}

			String stringValue = objectValue.toString();
			if (stringValue.length() > 0) {
				return stringValue.split(SPLIT_PATTERN);
			}
		}
		//Throw MachineAdapter Exception if the subscriptions is invalid
		invalidDataSubscription();
		return new String[0];
	}

	/*
	 * Throw MachineAdapter Exception if the subscriptions is invalid
	 */
	private void invalidDataSubscription() {
		// data subscriptions must not be empty.
		String msg = "SampleSubscriptionAdapter.dataSubscriptions.invalid"; //$NON-NLS-1$
		_logger.error(msg);
		throw new MachineAdapterException(msg);
	}

	/**
	 * OSGi component lifecycle deactivation method
	 *
	 * @param ctx
	 *            component context
	 */
	@Deactivate
	public void deactivate(ComponentContext ctx) {
		// Put your clean up code here when container is shutting down
		if (_logger.isDebugEnabled()) {
			_logger.debug("Stopped sample for " + ctx.getBundleContext().getBundle().getSymbolicName()); //$NON-NLS-1$
		}

		Collection<SampleDataSubscription> values = this.dataSubscriptions.values();
		// Stop random data generation thread.
		for (SampleDataSubscription sub : values) {
			sub.stop();
		}
		this.adapterState = MachineAdapterState.Stopped;
	}

	/**
	 * OSGi component lifecycle modified method. Called when the component
	 * properties are changed.
	 *
	 * @param ctx
	 *            component context
	 */
	@Modified
	public synchronized void modified(ComponentContext ctx) {
		// Handle run-time changes to properties.
	}

	/*
	 * ####################################### # IMachineAdapter interface
	 * methods # #######################################
	 */

	@Override
	public UUID getId() {
		return this.uuid;
	}

	@Override
	public MachineAdapterInfo getInfo() {
		return this.adapterInfo;
	}

	@Override
	public MachineAdapterState getState() {
		return this.adapterState;
	}

	/*
	 * Returns all data nodes. Data nodes are auto-generated at startup.
	 */
	@Override
	public List<PDataNode> getNodes() {
		return new ArrayList<PDataNode>(this.dataNodes.values());
	}

	/*
	 * Reads data from data cache. Data cache always contains latest values.
	 */
	@Override
	public PDataValue readData(UUID nodeId) throws MachineAdapterException {
		PDataValue pDataValue = new PDataValue(nodeId);
		// DecimalFormat df = new DecimalFormat("####.##"); //$NON-NLS-1$
		SampleDataNode node = this.dataNodes.get(nodeId);
		double fvalue = generateRandomUsageValue(node.getNode());
		//_logger.info("fvalue=" + fvalue );
		// if (node.getNode().getExpression() != null && !"".equals(node.getNode().getExpression())) { //$NON-NLS-1$
		// 	String expr = node.getNode().getExpression();
		// 	fvalue = eval(expr.replaceAll("#NODE_VALUE#", Double.toString(fvalue))); //$NON-NLS-1$
		// 	_logger.info("fvalue2=" + fvalue );
		// }
		PEnvelope envelope = new PEnvelope(fvalue);
		pDataValue = new PDataValue(node.getNodeId(), envelope);
		pDataValue.setNodeName(node.getName());
		//pDataValue.setAddress(node.getAddress());
		// Do not return null.
		return pDataValue;
	}

	/*
	 * Writes data value into data cache.
	 */
	@Override
	public void writeData(UUID nodeId, PDataValue value) throws MachineAdapterException {
		if (this.dataValueCache.containsKey(nodeId)) {
			// Put data into cache. The value typically should be written to a
			// device node.
			this.dataValueCache.put(nodeId, value);
		}
	}

	/*
	 * ################################################### #
	 * ISubscriptionMachineAdapter interface methods #
	 * ###################################################
	 */

	/*
	 * Returns list of all subscriptions.
	 */
	@Override
	public List<IDataSubscription> getSubscriptions() {
		return new ArrayList<IDataSubscription>(this.dataSubscriptions.values());
	}

	/*
	 * Adds new data subscription into the list.
	 */
	@Override
	public synchronized UUID addDataSubscription(IDataSubscription subscription) throws MachineAdapterException {
		if (subscription == null) {
			throw new IllegalArgumentException("Subscription is null"); //$NON-NLS-1$
		}

		List<PDataNode> subscriptionNodes = new ArrayList<PDataNode>();

		// Add new data subscription.
		if (!this.dataSubscriptions.containsKey(subscription.getId())) {
			// Make sure that new subscription contains valid nodes.
			for (PDataNode node : subscription.getSubscriptionNodes()) {
				if (!this.dataNodes.containsKey(node.getNodeId())) {
					throw new MachineAdapterException("Node doesn't exist for this adapter"); //$NON-NLS-1$
				}

				subscriptionNodes.add(this.dataNodes.get(node.getNodeId()));
			}

			// Create new subscription.
			SampleDataSubscription newSubscription = new SampleDataSubscription(this, subscription.getName(),
					subscription.getUpdateInterval(), subscriptionNodes,spillway);
			this.dataSubscriptions.put(newSubscription.getId(), newSubscription);
			new Thread(newSubscription).start();
			return newSubscription.getId();
		}

		return null;
	}

	/*
	 * Remove data subscription from the list
	 */
	@Override
	public synchronized void removeDataSubscription(UUID subscriptionId) {
		// Stop subscription, notify all subscribers, and remove subscription
		if (this.dataSubscriptions.containsKey(subscriptionId)) {
			this.dataSubscriptions.get(subscriptionId).stop();
			this.dataSubscriptions.remove(subscriptionId);
		}
	}

	/**
	 * get subscription given subscription id.
	 */
	@Override
	public IDataSubscription getDataSubscription(UUID subscriptionId) {
		if (this.dataSubscriptions.containsKey(subscriptionId)) {
			return this.dataSubscriptions.get(subscriptionId);
		}
		throw new MachineAdapterException("Subscription does not exist"); //$NON-NLS-1$
	}

	@Override
	public synchronized void addDataSubscriptionListener(UUID dataSubscriptionId, IDataSubscriptionListener listener)
			throws MachineAdapterException {
		if (this.dataSubscriptions.containsKey(dataSubscriptionId)) {
			this.dataSubscriptions.get(dataSubscriptionId).addDataSubscriptionListener(listener);
			return;
		}
		throw new MachineAdapterException("Subscription does not exist"); //$NON-NLS-1$
	}

	@Override
	public synchronized void removeDataSubscriptionListener(UUID dataSubscriptionId,
			IDataSubscriptionListener listener) {
		if (this.dataSubscriptions.containsKey(dataSubscriptionId)) {
			this.dataSubscriptions.get(dataSubscriptionId).removeDataSubscriptionListener(listener);
		}
	}

	/*
	 * ##################################### # Private methods #
	 * #####################################
	 */

	/**
	 * Generates random nodes
	 *
	 * @param count
	 *            of nodes
	 */
	private void createNodes(List<JsonDataNode> nodes) {
		this.dataNodes.clear();
		for (JsonDataNode jsonNode : nodes) {
			SampleDataNode node = new SampleDataNode(this.uuid, jsonNode);
			// Create a new node and put it in the cache.
			this.dataNodes.put(node.getNodeId(), node);

		}
	}

	// Put data into data cache.
	/**
	 * @param values
	 *            list of values
	 */
	protected void putData(List<PDataValue> values) {
		for (PDataValue value : values) {
			this.dataValueCache.put(value.getNodeId(), value);
		}
	}

	@Override
	public void addSubscriptionAdapterListener(ISubscriptionAdapterListener arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void removeSubscriptionAdapterListener(ISubscriptionAdapterListener arg0) {
		// TODO Auto-generated method stub

	}

	private Double generateRandomUsageValue(JsonDataNode node) {
		double start = node.getLowerThreshold();
		if (node.getSimulateAlarm()) {
			start = node.getUpperThreshold();
		}
		return new Double(this.decimalFormat
				.format(start + Math.random() * (node.getUpperThreshold() - node.getLowerThreshold())));
	}

	/**
	 * @return the configNodes
	 */
	public List<JsonDataNode> getConfigNodes() {
		return this.configNodes;
	}

	/**
	 * @param configNodes
	 *            the configNodes to set
	 */
	public void setConfigNodes(List<JsonDataNode> configNodes) {
		this.configNodes = configNodes;
		createNodes(this.configNodes);
	}

	/**
	 * @param expression
	 *            -
	 * @return -
	 */
	public double eval(String expression) {
		Expression expr;
		try {
			expr = Parser.parse(expression);
			return expr.evaluate();
		} catch (ParseException e) {
			throw new RuntimeException("Exception when parsing expression", e); //$NON-NLS-1$
		}
	}

	/**
	 * @param newNodes -
	 * @return -
	 */
	@PUT
	@Path("/saveconfig")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
	public DataSimulatorResponse changeSimulatorConfig(String newNodes) {
		DataSimulatorResponse response = new DataSimulatorResponse();
		ObjectMapper mapper = new ObjectMapper();
		List<JsonDataNode> newDataNodes = new ArrayList<JsonDataNode>();
		try {
			newDataNodes = mapper.readValue(newNodes, new TypeReference<List<JsonDataNode>>() {
				// type name = new type();
			});
		} catch (JsonParseException e1) {
			throw new RuntimeException(e1.getMessage(), e1);
		} catch (JsonMappingException e1) {
			throw new RuntimeException(e1.getMessage(), e1);
		} catch (IOException e1) {
			throw new RuntimeException(e1.getMessage(), e1);
		}

		try {
			copyFile(MACHINE_HOME + File.separator + this.config.getNodeConfigFile(),
					MACHINE_HOME + File.separator + this.config.getNodeConfigFile() + "_bck"); //$NON-NLS-1$
			File f = new File(MACHINE_HOME + File.separator + this.config.getNodeConfigFile());
			if (newDataNodes != null) {
				this.setConfigNodes(newDataNodes);
			}
			mapper.writerWithDefaultPrettyPrinter().writeValue(f, newDataNodes);

			response.setResponseString("Simulator Config Changed"); //$NON-NLS-1$
			response.setResponseCode(200);
		} catch (Throwable e) {
			e.printStackTrace();
			response.setIsError(Boolean.TRUE);
			response.setErrorMessage(e.getMessage());
			response.setResponseCode(500);
		}
		return response;
	}

	/**
	 * @return -
	 */
	@GET
	@Path("/getconfig")
	@Produces(MediaType.APPLICATION_JSON)
	public List<JsonDataNode> listExistingConfig() {
		return this.getConfigNodes();
	}

	/**
	 * @return -
	 */
	@GET
	@Path("/alarm")
	@Produces("application/json")
	public DataSimulatorResponse simulateAlarm() {
		for (JsonDataNode node : this.getConfigNodes()) {
			node.setSimulateAlarm(Boolean.TRUE);
		}
		DataSimulatorResponse response = new DataSimulatorResponse();
		response.setResponseString("Alarm Simulation started"); //$NON-NLS-1$
		response.setResponseCode(200);
		return response;
	}

	private void copyFile(String sourceFile, String targetFile) {
		_logger.info("Copying " + sourceFile + " to " + targetFile); //$NON-NLS-1$ //$NON-NLS-2$
		File source = new File(sourceFile);
		File target = new File(targetFile);

		try (InputStream inStream = new FileInputStream(source);
				OutputStream outStream = new FileOutputStream(target);) {

			byte[] buffer = new byte[1024];

			int length;
			// copy the file content in bytes
			while ((length = inStream.read(buffer)) > 0) {
				outStream.write(buffer, 0, length);
			}
			_logger.info(sourceFile + " backed up successfully to " + targetFile); //$NON-NLS-1$
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public List<PDataNode> getEdgeDataNodes() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public UUID addEdgeDataSubscription(IEdgeDataSubscription arg0) throws MachineAdapterException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public IEdgeDataSubscription getEdgeDataSubscription(UUID arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<IEdgeDataSubscription> getEdgeDataSubscriptions() {
		return new ArrayList<IEdgeDataSubscription>();
	}

	@Override
	public void removeEdgeDataSubscription(UUID arg0) {
		// TODO Auto-generated method stub

	}

	public ISampleAdapterConfig getConfig() {
		return config;
	}

	@Reference
	public void setConfig(ISampleAdapterConfig config) {
		this.config = config;
	}

	@Reference
	public void setSpillway(ISpillway spillway) {
		this.spillway = spillway;
	}
}
