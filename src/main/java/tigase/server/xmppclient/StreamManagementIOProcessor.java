/*
 * Tigase XMPP Server - The instant messaging server
 * Copyright (C) 2004 Tigase, Inc. (office@tigase.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.server.xmppclient;

import tigase.annotations.TigaseDeprecated;
import tigase.component.exceptions.ComponentException;
import tigase.kernel.beans.Bean;
import tigase.kernel.beans.Inject;
import tigase.kernel.beans.config.ConfigField;
import tigase.net.IOServiceListener;
import tigase.net.SocketThread;
import tigase.server.*;
import tigase.stats.StatisticsList;
import tigase.util.common.TimerTask;
import tigase.util.dns.DNSResolverFactory;
import tigase.xml.Element;
import tigase.xmpp.*;
import tigase.xmpp.impl.MessageCarbons;
import tigase.xmpp.jid.JID;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class implements XEP-0198 Stream Management
 *
 * @author andrzej
 */
@Bean(name = StreamManagementIOProcessor.XMLNS, parent = ClientConnectionManager.class, active = true)
public class StreamManagementIOProcessor
		implements XMPPIOProcessor {

	public static final String XMLNS = "urn:xmpp:sm:3";
	private static final Logger log = Logger.getLogger(StreamManagementIOProcessor.class.getCanonicalName());
	// used tag names
	private static final String ACK_NAME = "a";
	private static final String ENABLE_NAME = "enable";
	private static final String ENABLED_NAME = "enabled";
	private static final String REQ_NAME = "r";
	private static final String RESUME_NAME = "resume";
	private static final String RESUMED_NAME = "resumed";

	// used attribute names
	private static final String H_ATTR = "h";
	private static final String LOCATION_ATTR = "location";
	private static final String RESUME_ATTR = "resume";
	private static final String MAX_ATTR = "max";
	private static final String PREVID_ATTR = "previd";

	// various strings used as key to store data in maps
	private static final String ACK_REQUEST_COUNT_KEY = "ack-request-count";
	private static final String ACK_REQUEST_MIN_DELAY_KEY = "ack-request-min-delay";
	private static final int DEF_ACK_REQUEST_COUNT_VAL = 10;
	private static final String[] DELAY_PATH = {Message.ELEM_NAME, "delay"};
	private static final String DELAY_XMLNS = "urn:xmpp:delay";
	private static final String IGNORE_UNDELIVERED_PRESENCE_KEY = "ignore-undelivered-presence";
	private static final String IN_COUNTER_KEY = XMLNS + "_in";
	private static final String MAX_RESUMPTION_TIMEOUT_KEY = XMLNS + "_resumption-timeout";
	private static final String MAX_RESUMPTION_TIMEOUT_PROP_KEY = "max-resumption-timeout";
	private static final String OUT_COUNTER_KEY = XMLNS + "_out";
	private static final String RESUMPTION_TASK_KEY = XMLNS + "_resumption-task";
	private static final String RESUMPTION_TIMEOUT_PROP_KEY = "resumption-timeout";
	private static final String RESUMPTION_TIMEOUT_START_KEY = "resumption-timeout-start";
	private static final String STREAM_ID_KEY = XMLNS + "_stream_id";

	private static final Element[] FEATURES = {new Element("sm", new String[]{"xmlns"}, new String[]{XMLNS})};

	private static final SimpleDateFormat formatter;

	static {
		formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	private final ConcurrentHashMap<String, XMPPIOService> services = new ConcurrentHashMap<String, XMPPIOService>();
	@ConfigField(desc = "Number of sent packets after should ask for confirmation of delivery", alias = ACK_REQUEST_COUNT_KEY)
	private int ack_request_count = DEF_ACK_REQUEST_COUNT_VAL;
	@Inject(bean = "service")
	private ConnectionManager connectionManager;
	@ConfigField(desc = "Ignore undelivered presence packets", alias = IGNORE_UNDELIVERED_PRESENCE_KEY)
	private boolean ignoreUndeliveredPresence = true;
	@ConfigField(desc = "Maximal allowed time for session resumption", alias = MAX_RESUMPTION_TIMEOUT_PROP_KEY)
	private int max_resumption_timeout = 15 * 60;
	@ConfigField(desc = "Default resumption timeout", alias = RESUMPTION_TIMEOUT_PROP_KEY)
	private int resumption_timeout = 60;
	@ConfigField(desc = "Max allowed queue size of unacked packets", alias = "max-resumption-queue-size")
	private int max_queue_size = 2000;
	@ConfigField(desc = "Maximal burst period for queue size", alias = "max-resumption-queue-size-burst-period")
	private int max_resumption_queue_burst_period = 60;
	@ConfigField(desc = "Maximal burst queue size ratio", alias = "max-resumption-queue-size-burst-ratio")
	private int max_resumption_queue_burst_ratio = 20;
	@ConfigField(desc = "Time since last ack received or ack request sent before which ack request should not be sent", alias = ACK_REQUEST_MIN_DELAY_KEY)
	private long ack_request_min_delay = 200l;

	static AtomicLong totalQueueSize = new AtomicLong();
	/**
	 * Method returns true if XMPPIOService has enabled SM.
	 *
	 */
	public static boolean isEnabled(XMPPIOService service) {
		return service.getSessionData().containsKey(IN_COUNTER_KEY);
	}

	private static boolean isResumptionEnabled(XMPPIOService service) {
		return service.getSessionData().containsKey(STREAM_ID_KEY);
	}

	public StreamManagementIOProcessor() {
	}

	@Override
	public String getId() {
		return XMLNS;
	}

	@Override
	public Element[] supStreamFeatures(XMPPIOService service) {
		// user jid is set after authentication and then after resource bind,
		// so it should be available after authentication
		// but in rare cases service can be null if connection was already closed..
		if (service == null || service.getUserJid() == null)
			return null;
		if (isEnabled(service)) {
			return null;
		}

		return FEATURES;
	}
	
	private String enable(XMPPIOService service, boolean withResumption, Integer maxTimeout) {
		OutQueue outQueue = newOutQueue();
		outQueue.setAckRequestCount(ack_request_count);
		service.getSessionData().putIfAbsent(OUT_COUNTER_KEY, outQueue);
		service.getSessionData().putIfAbsent(IN_COUNTER_KEY, newCounter());

		String id = null;
		int timeout = resumption_timeout;
		if (resumption_timeout > 0 && withResumption) {
			outQueue.setResumptionEnabled(true);
			if (maxTimeout != null) {
				timeout = Math.min(max_resumption_timeout, maxTimeout);
			}
			id = UUID.randomUUID().toString();
			service.getSessionData().putIfAbsent(STREAM_ID_KEY, id);
			service.getSessionData().put(MAX_RESUMPTION_TIMEOUT_KEY, timeout);

			services.put(id, service);
		}

		Packet cmd = StreamManagementCommand.ENABLED.create(service.getConnectionId(), service.getDataReceiver());
		cmd.setPacketFrom(service.getConnectionId());
		cmd.setPacketTo(service.getDataReceiver());
		Command.addFieldValue(cmd, "resumption-id", id);
		connectionManager.processOutPacket(cmd);

		return id;
	}

	@Override
	public boolean processIncoming(XMPPIOService service, Packet packet) {
		if (!isEnabled(service)) {
			if (packet.getXMLNS() != XMLNS) {
				return false;
			} else if (packet.getElemName() == ENABLE_NAME) {
				String maxStr = packet.getElement().getAttributeStaticStr(MAX_ATTR);
				String id = enable(service, packet.getElement().getAttributeStaticStr(RESUME_ATTR) != null,
								   maxStr != null ? Integer.parseInt(maxStr) : null);
				String location = id != null ? DNSResolverFactory.getInstance().getSecondaryHost() : null;
				Integer timeout = (Integer) service.getSessionData().get(MAX_RESUMPTION_TIMEOUT_KEY);
				
				try {
					service.writeRawData("<" + ENABLED_NAME + " xmlns='" + XMLNS + "'" +
												 (id != null ? " id='" + id + "' " + RESUME_ATTR + "='true' " +
														 MAX_ATTR + "='" + timeout + "'" : "") +
												 (location != null ? " " + LOCATION_ATTR + "='" + location + "'" : "") +
												 " />");
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, "Started StreamManagement with resumption timeout set to = {1} [{0}]",
								new Object[]{service.toString(), (id != null ? resumption_timeout : null)});
					}
				} catch (IOException ex) {
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, service.toString() + ", exception during sending <enabled/>, stopping...",
								ex);
					}
					service.forceStop();
				}
				return true;
			} else if (packet.getElemName() == RESUME_NAME) {
				String h = packet.getElement().getAttributeStaticStr(H_ATTR);
				String id = packet.getElement().getAttributeStaticStr(PREVID_ATTR);

				try {
					resumeStream(service, id, Integer.parseInt(h));
				} catch (IOException ex) {
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, service.toString() + ", exception while resuming stream for user " +
								service.getUserJid() + " with id " + id, ex);
					}

					service.forceStop();
				}
				return true;
			} else {
				return false;
			}
		}
		if (packet.getXMLNS() == XMLNS) {
			if (packet.getElemName() == ACK_NAME) {
				String hStr = packet.getAttributeStaticStr(H_ATTR);

				int h = Integer.parseInt(hStr);
				OutQueue outQueue = (OutQueue) service.getSessionData().get(OUT_COUNTER_KEY);
				if (outQueue != null) {
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, "Acking {2} packets, in outQueue: {3} while processing: {1} [{0}]",
								new Object[]{service, packet, h, outQueue.waitingForAck()});
					}
					outQueue.ack(h);
				} else {
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, "outQueue already null while processing: {1} [{0}]",
								new Object[]{service, packet});
					}
				}
			} else if (packet.getElemName() == REQ_NAME) {
				int value = ((Counter) service.getSessionData().get(IN_COUNTER_KEY)).get();

				try {
					service.writeRawData(
							"<" + ACK_NAME + " xmlns='" + XMLNS + "' " + H_ATTR + "='" + String.valueOf(value) + "'/>");
				} catch (IOException ex) {
					if (log.isLoggable(Level.FINE)) {
						log.log(Level.FINE, service.toString() + ", exception during sending <a/> as " +
								"response for <r/>, not stopping serivce as it " +
								"will be stopped after processing all incoming data...", ex);
					}
					//service.forceStop();
				}
			}
			return true;
		}

		if (!isStanza(packet)) {
			return false;
		}

		((Counter) service.getSessionData().get(IN_COUNTER_KEY)).inc();

		return false;
	}

	@Override
	public boolean processOutgoing(XMPPIOService service, Packet packet) {
		if (!isEnabled(service) || packet.getXMLNS() == XMLNS) {
			return false;
		}

		// stream resumption should queue only stanzas (iq, presence, message)
		if (!isStanza(packet)) {
			return false;
		}

		if (service.getAuthorisedUserJid().isPresent() && packet.getStanzaTo() == null) {
			packet.initVars(packet.getStanzaFrom(), (JID)service.getAuthorisedUserJid().get());
		}

		OutQueue outQueue = (OutQueue) service.getSessionData().get(OUT_COUNTER_KEY);
		if (outQueue == null) {
			OutQueue.Entry e = new OutQueue.Entry(packet);
			connectionManager.processUndeliveredPacket(e.getPacketWithStamp(), e.stamp, null);
		} else {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Queuing StreamManagement packet: {1}, queue size: {2} [{0}]", new Object[]{service, packet, outQueue.waitingForAck()});
			}
			if (!outQueue.append(packet, max_queue_size, max_resumption_timeout, max_resumption_queue_burst_period,
								 max_resumption_queue_burst_ratio)) {
				// it is too long without confirmation or queue is too big, we need to cancel this connection.
				try {
					service.getSessionData().put(RESUMPTION_TIMEOUT_START_KEY, 0L);
					service.writeRawData("<stream:error xmlns:stream=\"http://etherx.jabber.org/streams\">" +
												 "<policy-violation xmlns=\"urn:ietf:params:xml:ns:xmpp-streams\"/>" +
												 "<text xmlns=\"urn:ietf:params:xml:ns:xmpp-streams\">Too many unacked stanzas</text>" +
												 "</stream:error>");
				} catch (IOException ex) {
					// we do not care if exception happened as we already are closing this connection
				}
				// if we will be polite using stop() queues can still grow.. so let's stop being polite..
				// in worst case if queue was too large error may not be sent
				service.forceStop();
			}
		}

		return service.getSessionData().containsKey(RESUMPTION_TASK_KEY);
	}

	@Override
	public void packetsSent(XMPPIOService service) throws IOException {
		if (!isEnabled(service)) {
			return;
		}

		OutQueue outQueue = (OutQueue) service.getSessionData().get(OUT_COUNTER_KEY);
		if (outQueue != null && shouldRequestAck(service, outQueue)) {
			outQueue.sendingRequest();
			service.writeRawData("<" + REQ_NAME + " xmlns='" + XMLNS + "' />");
		}
	}

	@Override
	public void processCommand(XMPPIOService service, Packet pc) {
		if (pc.getType() == StanzaType.error || pc.getType() == StanzaType.result) {
			return;
		}
		StreamManagementCommand cmd = StreamManagementCommand.fromPacket(pc);
		switch (cmd) {
			case STREAM_MOVED:
				if (service == null) {
					// it looks like the sender of this packet already was disconnected, ignoring request
					return;
				}
				String newConn = Command.getFieldValue(pc, "new-conn-jid");

				String id = (String) service.getSessionData().get(STREAM_ID_KEY);

				JID newConnJid = JID.jidInstanceNS(newConn);
				XMPPIOService newService = connectionManager.getXMPPIOService(newConnJid.getResource());

				// if connection was closed during resumption, then close
				// old connection as it would not be able to resume
				if (newService != null) {
					if (log.isLoggable(Level.FINEST)) {
						log.log(Level.FINEST, "stream for user {2} moved from {0} to {1}",
								new Object[]{service.getConnectionId(), newService.getConnectionId(),
											 newService.getUserJid()});
					}
					try {
						newService.setUserJid(service.getUserJid());
						if (!"false".equals(Command.getFieldValue(pc, "send-response"))) {
							Counter inCounter = (Counter) newService.getSessionData().get(IN_COUNTER_KEY);
							newService.writeRawData(
									"<" + RESUMED_NAME + " xmlns='" + XMLNS + "' " + PREVID_ATTR + "='" + id + "' " + H_ATTR +
											"='" + inCounter.get() + "' />");
						}

						service.getSessionData().put("stream-closed", "stream-closed");
						services.put(id, newService);

						// resending packets thru new connection
						OutQueue outQueue = (OutQueue) newService.getSessionData().get(OUT_COUNTER_KEY);
						List<OutQueue.Entry> packetsToResend = new ArrayList<OutQueue.Entry>(outQueue.getQueue());
						if (log.isLoggable(Level.FINE)) {
							log.log(Level.FINE, "resuming stream with id = {1} resending unacked packets = {2} [{0}]",
									new Object[]{service, id, outQueue.waitingForAck()});
						}
						for (OutQueue.Entry entry : packetsToResend) {
							Packet packetToResend = entry.getPacketWithStamp();
							if (log.isLoggable(Level.FINEST)) {
								log.log(Level.FINEST, "resuming stream with id = {1} resending unacked packet = {2} [{0}]",
										new Object[]{service, id, packetToResend});
							}
							newService.addPacketToSend(packetToResend);
						}

						// if there is any packet waiting we need to write them to socket
						// and to do that we need to call processWaitingPackets();
						if (!packetsToResend.isEmpty()) {
							if (newService.writeInProgress.tryLock()) {
								try {
									newService.processWaitingPackets();
									SocketThread.addSocketService(newService);
								} catch (Exception e) {
									log.log(Level.WARNING, newService + "Exception during writing packets: ", e);
									try {
										newService.stop();
									} catch (Exception e1) {
										log.log(Level.WARNING, newService + "Exception stopping XMPPIOService: ", e1);
									}    // end of try-catch
								} finally {
									newService.writeInProgress.unlock();
								}
							}
						}
					} catch (IOException ex) {
						if (log.isLoggable(Level.FINEST)) {
							log.log(Level.FINEST,
									"could not confirm session resumption for user = " + newService.getUserJid(), ex);
						}

						// remove new connection if resumption failed
						services.remove(id, service);
						services.remove(id, newService);
					}
				} else {
					if (log.isLoggable(Level.FINEST)) {
						log.log(Level.FINEST,
								"no new service available for user {0} to resume from {1}," + " already closed?",
								new Object[]{service.getUserJid(), service});
					}
				}

				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "closing old service {0} for user {1}",
							new Object[]{service, service.getUserJid()});
				}

				// stopping old service
				connectionManager.serviceStopped(service);
				break;
			case MOVE_STREAM: {
				String resumptionId = Command.getFieldValue(pc, "resumption-id");
				int h = Integer.parseInt(Command.getFieldValue(pc, "h"));
				try {
					String oldConnId = moveStream(service, resumptionId, h);

					Packet response = pc.okResult(new Element(Command.COMMAND_EL, new String[]{"xmlns"}, new String[]{Command.XMLNS}), 0);
					Counter inCounter = (Counter) service.getSessionData().get(IN_COUNTER_KEY);
					Command.addFieldValue(response, "h", String.valueOf(inCounter.counter));
					connectionManager.processOutPacket(response);

					response = StreamManagementCommand.STREAM_MOVED.create(service.getConnectionId(), service.getDataReceiver());
					response.setPacketFrom(service.getConnectionId());
					response.setPacketTo(service.getDataReceiver());
					Command.addFieldValue(response, "old-conn-jid", oldConnId);
					Command.addFieldValue(response, "send-response", "false");
					connectionManager.processOutPacket(response);

				} catch (Exception ex) {
					try {
						connectionManager.processOutPacket(
								Authorization.ITEM_NOT_FOUND.getResponseMessage(pc, ex.getMessage(), false));
					} catch (PacketErrorTypeException e) {
						// nothing to do..
					}
				}
			}
			break;
			case ENABLE: {
				String maxStr = Command.getFieldValue(pc, "max");
				Integer max = maxStr != null ? Integer.parseInt(maxStr) : null;
				boolean withResumption = Command.getCheckBoxFieldValue(pc, "resume");
				String resumptionId = enable(service, withResumption, max);

				Packet response = pc.okResult(new Element(Command.COMMAND_EL, new String[]{"xmlns"}, new String[]{Command.XMLNS}),0);
				if (resumptionId != null) {
					Command.addFieldValue(response, "id", resumptionId);

					Command.addFieldValue(response, "location", DNSResolverFactory.getInstance().getSecondaryHost());
					Integer timeout = (Integer) service.getSessionData().get(MAX_RESUMPTION_TIMEOUT_KEY);
					if (timeout != null) {
						Command.addFieldValue(response,"max", String.valueOf(timeout));
					}
				}
				connectionManager.processOutPacket(response);
			}
			break;
			default:
				break;
		}
	}

	@Override
	public boolean serviceStopped(XMPPIOService service, boolean streamClosed) {
		if (!isEnabled(service)) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Service stopped - StreamManagement disabled [{0}]", new Object[]{service});
			}
			return false;
		}

		String id = (String) service.getSessionData().get(STREAM_ID_KEY);

		if (streamClosed) {
			service.getSessionData().remove(STREAM_ID_KEY);
		}

//		try {
//			throw new Exception();
//		} catch (Throwable ex) {
//			log.log(Level.WARNING, "resumption timeout started, stream close = " + streamClosed, ex);
//			ex.printStackTrace();
//		}
		Long resumptionTimeoutStart = (Long) service.getSessionData().get(RESUMPTION_TIMEOUT_START_KEY);
		if (resumptionTimeoutStart != null) {
			final boolean isBeyondResumptionTimeout = (System.currentTimeMillis() - resumptionTimeoutStart) > (1 * resumption_timeout * 1000);
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST,
						"Service stopped - checking resumption timeout, resumptionTimeoutStart: {1}, resumption_timeout: {2}, streamClosed: {3} [{0}]",
						new Object[]{service, resumptionTimeoutStart, resumption_timeout, streamClosed});
			}
			// if resumptionTimeoutStart is set let's check if resumption was
			// not started for longer time than twice value of resumption_timeout
			// wojtek@2021-09-03: after discussion with andrzej we decided to use "once" times resumption timeout
			// as there wasn't sufficient reason to use double the value - if the resumption was not finished within
			// established timeout then it's considered failed.
			if (isBeyondResumptionTimeout || streamClosed) {

				if (id == null) {
					// connection already removed by another thread. It will take care of the rest
					return false;
				}

				// if so we should assume that resumption failed so we should
				// send errors, remove reference to service and stop this service
				services.remove(id, service);
				service.clearWaitingPackets();
				connectionManager.serviceStopped(service);
				// for case in which task was started but later </stream:stream> was found in remaining data
				TimerTask timerTask = (TimerTask) service.getSessionData().get(RESUMPTION_TASK_KEY);
				if (timerTask != null) {
					timerTask.cancel();
				}
				sendErrorsForQueuedPackets(service);
			}
			return false;
		}

		// some buggy client (ie. Psi) may close stream without sending stream
		// close which forces us to thread this stream as broken and waiting for
		// resumption but those clients are not compatible with XEP-0198 and
		// resumption so this should not happen
		if (isResumptionEnabled(service)) {
			if (service.getSessionData().getOrDefault(XMPPIOService.STREAM_CLOSING, false) == Boolean.TRUE) {
				services.remove(id, service);
				connectionManager.serviceStopped(service);
				sendErrorsForQueuedPackets(service);
				return false;
			}

			if (!services.containsKey(id)) {
				if (log.isLoggable(Level.FINEST)) {
					log.log(Level.FINEST, "Service stopped - resumption enabled but service not available [{0}]",
							new Object[]{service});
				}
				return false;
			}

			// ConnectionManager must not be notified about closed connection
			// but connection needs to be closed so this is this case we still
			// return false to call forceStop but we remove IOServiceListener
			service.setIOServiceListener((IOServiceListener) null);

			int resumptionTimeout = (Integer) service.getSessionData().get(MAX_RESUMPTION_TIMEOUT_KEY);
			synchronized (service) {
				if (!service.getSessionData().containsKey(RESUMPTION_TASK_KEY)) {
					TimerTask timerTask = new ResumptionTimeoutTask(service);
					service.getSessionData().put(RESUMPTION_TASK_KEY, timerTask);
					connectionManager.addTimerTask(timerTask, resumptionTimeout * 1000);

					// set timestamp of begining of resumption to be able to detect
					// if something went wrong during resumption and service is
					// still kept in connection manager services as active service
					// after twice as long as resumption timeout
					service.getSessionData().put(RESUMPTION_TIMEOUT_START_KEY, System.currentTimeMillis());
					service.clearWaitingPackets();
					if (log.isLoggable(Level.FINEST)) {
						log.log(Level.FINEST, "Service stopped - resumption enabled and timeout started [{0}]",
								new Object[]{service});
					}
				}
			}

			return false;
		} else if (id != null) {
			services.remove(id, service);
		}

		if (log.isLoggable(Level.FINEST)) {
			log.log(Level.FINEST, "Service stopped - resumption disabled, sending unacked packets [{0}]",
					new Object[]{service});
		}

		service.clearWaitingPackets();
		connectionManager.serviceStopped(service);
		sendErrorsForQueuedPackets(service);
		return false;
	}

	@Override
	public void getStatistics(StatisticsList list) {
		if (list.checkLevel(Level.FINEST)) {
			list.add(connectionManager.getName() + "/" + getId(), "Number of resume services", services.size(), Level.FINEST);
			list.add(connectionManager.getName() + "/" + getId(), "Total size of all queues", totalQueueSize.get(), Level.FINEST);
		}
	}

	@Override
	public void streamError(XMPPIOService service, StreamError streamErrorName) {
	}

	/**
	 * Override this method to define a custom behaviour for request ack. The default implementation will request an ack
	 * if there are more than {@link #ack_request_count} packets waiting since last request for ack and last ack request
	 * was not sent in last X ms.
	 */
	protected boolean shouldRequestAck(XMPPIOService service, OutQueue outQueue) {
		// send request for ack if there is at least X message since last ack or request for ack
		if (Math.min(outQueue.unackedSinceLastRequest(), outQueue.waitingForAck()) >= ack_request_count) {
			// do not send ack request if there was less than X ms since previous request
			if (outQueue.gotAckOrSentRequestSince(System.currentTimeMillis() - ack_request_min_delay)) {
				return false;
			}
			return true;
		}

		return false;
	}

	protected Counter newCounter() {
		return new Counter();
	}

	protected OutQueue newOutQueue() {
		return new OutQueue();
	}

	protected boolean isStanza(Packet packet) {
		return switch (packet.getElemName()) {
			case Iq.ELEM_NAME, Message.ELEM_NAME, Presence.ELEM_NAME -> true;
			default -> false;
		};
	}

	public static class ResumptionException extends ComponentException {

		public ResumptionException(Authorization errorCondition) {
			super(errorCondition);
		}
	}

	private String moveStream(XMPPIOService service, String id, int h) throws IOException, ResumptionException {
		XMPPIOService oldService = services.get(id);
		if (oldService == null || !isSameUser(oldService, service)) {
			// should send failed!
			throw new ResumptionException(Authorization.ITEM_NOT_FOUND);
		}

		// if stream has resource binded then we should not resume
		if (service.getUserJid() != null && JID.jidInstanceNS(service.getUserJid()).getResource() != null) {
			throw new ResumptionException(Authorization.UNEXPECTED_REQUEST);
		}

		if (services.remove(id, oldService)) {
			synchronized (oldService) {
				// we should cancel the timer but not remove it as in other case Watchdog can "recreate" timer task
				// and restart resumption process
				TimerTask timerTask = (TimerTask) oldService.getSessionData().get(RESUMPTION_TASK_KEY);
				if (timerTask != null) {
					timerTask.cancel();
				}
				oldService.clearWaitingPackets();
			}

			// get old out queue
			OutQueue outQueue = (OutQueue) oldService.getSessionData().get(OUT_COUNTER_KEY);
			if (log.isLoggable(Level.FINE)) {
				log.log(Level.FINE,
						"Resuming stream with id = {1} with {2} packets waiting for ack, local h = {3} and remote h = {4} [{0}]",
						new Object[]{service, id, outQueue.waitingForAck(), outQueue.get(), h});
			}
			outQueue.ack(h);

			// move required data from old XMPPIOService session data to new service session data
			service.getSessionData().put(OUT_COUNTER_KEY, outQueue);
			service.getSessionData()
					.put(MAX_RESUMPTION_TIMEOUT_KEY, oldService.getSessionData().get(MAX_RESUMPTION_TIMEOUT_KEY));
			service.getSessionData().put(IN_COUNTER_KEY, oldService.getSessionData().get(IN_COUNTER_KEY));
			service.getSessionData().put(STREAM_ID_KEY, oldService.getSessionData().get(STREAM_ID_KEY));

			return oldService.getConnectionId().toString();
		} else {
			throw new ResumptionException(Authorization.ITEM_NOT_FOUND);
		}
	}

	/**
	 * Method responsible for starting process of stream resumption
	 */
	private void resumeStream(XMPPIOService service, String id, int h) throws IOException {
		try {
			String oldConnId = moveStream(service, id,h);
			// send notification to session manager about change of connection
			// used for session
			Packet cmd = StreamManagementCommand.STREAM_MOVED.create(service.getConnectionId(), service.getDataReceiver());
			cmd.setPacketFrom(service.getConnectionId());
			cmd.setPacketTo(service.getDataReceiver());
			Command.addFieldValue(cmd, "old-conn-jid", oldConnId);
			Command.addFieldValue(cmd, "send-response", "true");
			connectionManager.processOutPacket(cmd);
		} catch (ResumptionException ex) {
			service.writeRawData("<failed xmlns='" + XMLNS + "'>" + "<" + ex.getName() + " xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
										 "</failed>");
		}
	}

	/**
	 * Verifies if connections are authenticate for same bare jid
	 * @return true - only when bare jids are the same
	 */
	private boolean isSameUser(XMPPIOService oldService, XMPPIOService newService) {
		if (oldService.getUserJid() == null || newService.getUserJid() == null) {
			return false;
		}

		JID oldUserJid = JID.jidInstanceNS(oldService.getUserJid());
		JID newUserJid = JID.jidInstanceNS(newService.getUserJid());

		return oldUserJid.getBareJID().equals(newUserJid.getBareJID());
	}

	/**
	 * Method responsible for sending recipient-unavailable error for all not acked packets
	 *
	 */
	private void sendErrorsForQueuedPackets(XMPPIOService service) {
		service.clearWaitingPackets();

		OutQueue outQueue = (OutQueue) service.getSessionData().remove(OUT_COUNTER_KEY);
		if (outQueue != null) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST,
						"Service stopped - returning errors for {1} packets in queue [{0}]",
						new Object[]{service, outQueue.waitingForAck()});
			}
			OutQueue.Entry e = null;

			while ((e = outQueue.queue.poll()) != null) {
				connectionManager.processUndeliveredPacket(e.getPacketWithStamp(), e.stamp, null);
			}
		}
	}

	/**
	 * Counter class implements proper counter with overflow from 2^32-1 to 0
	 */
	public static class Counter {

		private int counter = 0;

		/**
		 * Increment counter
		 */
		public void inc() {
			counter++;
			if (counter < 0) {
				counter = 0;
			}
		}

		/**
		 * Get value of counter.
		 */
		public int get() {
			return counter;
		}

		/**
		 * Sets value of a counter - use only for testing!
		 *
		 */
		protected void setCounter(int value) {
			this.counter = value;
		}
	}

	/**
	 * OutQueue class implements queue of outgoing packets waiting for ack with implementation of removing acked
	 * elements when id of acked packet is passed
	 */
	public static class OutQueue
			extends Counter {

		private final ArrayDeque<Entry> queue = new ArrayDeque<Entry>();
		private long lastConfirmationAt = 0;
		private long lastRequestSentAt = 0;
		private int lastRequestSentFor = 0;
		private int ackRequestCount = DEF_ACK_REQUEST_COUNT_VAL;
		private final LongSupplier timestampSupplier;

		public OutQueue() {
			this(System::currentTimeMillis);
		}

		protected OutQueue(LongSupplier timestampSupplier) {
			this.timestampSupplier = timestampSupplier;
		}
		
		/**
		 * Method determines if we should check packets for falling within timeout. Currently we only check
		 * the timeout if the queue size is bigger if the maximum count of packets that can be send
		 * to the server without the server requiring the ack. Otherwise, it could happen that idle or low
		 * traffic clients could be disconnected if the packets waiting for the ack were very old thus falling
		 * outside timeout but still few enough that wouldn't trigger ack request.
		 *
		 * @return {@code true} if there are fewer packets than maximum allow packet count that doesn't trigger
		 * server ack request, {@code false} otherwise.
		 */
		private boolean shouldCheckTimeout() {
			return queue.size() > (ackRequestCount + 3);
		}

		/**
		 * Append packet to waiting for ack queue
		 *
		 */
		@Deprecated
		@TigaseDeprecated(removeIn = "9.0.0", since = "8.2.0", note = "Use method with maxQueueSize")
		public boolean append(Packet packet, int timeoutInSec) {
			return append(packet, Integer.MAX_VALUE, timeoutInSec);
		}

		/**
		 * Append packet to waiting for ack queue
		 *
		 */
		@Deprecated
		@TigaseDeprecated(removeIn = "9.0.0", since = "8.2.0", note = "Use method with maxQueueSize")
		public boolean append(Packet packet, int maxQueueSize, int timeoutInSec) {
			return append(packet, maxQueueSize, timeoutInSec, 0, 1);
		}

		/**
		 * Append packet to waiting for ack queue
		 *
		 */
		public boolean append(Packet packet, int maxQueueSize, int timeoutInSec, int burstPeriodInSec, int burstRatio) {
			if (!packet.wasProcessedBy(XMLNS)) {

				long currentTimeMillis = timestampSupplier.getAsLong();
				// check if queue size does not exceed limit
				if (queue.size() >= maxQueueSize) {
					Entry first = queue.peekFirst();
					Long period = first != null ? currentTimeMillis - first.stamp : null;
					// queue is exceeded, but we should allow burst, ie. during reconnection
					if (log.isLoggable(Level.FINEST)) {
						log.finest(() -> "Queue size exceeded maxQueueSize: " + maxQueueSize + ", current size: " + queue.size() + ", period: " + period + "ms" + ", burst period: " + burstPeriodInSec + ", burst ratio: " + burstRatio);
					}
					if (period == null || period > ((long) burstPeriodInSec * 1000) || queue.size() >= (maxQueueSize * burstRatio)) {
						return false;
					}
				}

				if (shouldCheckTimeout()) {
					Entry first = queue.peekFirst();
					if (first != null && (currentTimeMillis - first.stamp > ((long)timeoutInSec * 1000))) {
						return false;
					}
				}
				packet.processedBy(XMLNS);
				queue.offer(new Entry(packet, timestampSupplier));
				totalQueueSize.incrementAndGet();
				inc();
				return true;
			}
			return true;
		}

		/**
		 * Confirm delivery of packets up to count passed as value
		 *
		 */
		public void ack(int value) {
			int count = get() - value;

			if (count < 0) {
				count = (Integer.MAX_VALUE - value) + get() + 1;
			}

			lastConfirmationAt = timestampSupplier.getAsLong();

			while (count < queue.size()) {
				queue.poll();
				totalQueueSize.decrementAndGet();
			}
		}

		/**
		 * Method notifies class that request for ack is being sent
		 */
		public void sendingRequest() {
			lastRequestSentAt = timestampSupplier.getAsLong();
			lastRequestSentFor = get();
		}

		/**
		 * Sets ack request count value
		 * @param ackRequestCount
		 */
		public void setAckRequestCount(int ackRequestCount) {
			this.ackRequestCount = ackRequestCount;
		}

		@Deprecated
		@TigaseDeprecated(removeIn = "9.0.0", since = "8.3.0", note = "Method will not be called any more")
		public void setResumptionEnabled(boolean enabled) {
		}

		/**
		 * Returns size of queue containing packets waiting for ack
		 */
		public int waitingForAck() {
			return queue.size();
		}

		/**
		 * Method returns internal queue with packets waiting for ack - use testing only!
		 */
		protected ArrayDeque<Entry> getQueue() {
			return queue;
		}

		/**
		 * Method returns timestamp of the last received ack.
		 * @return
		 */
		protected long getLastConfirmationAt() {
			return lastConfirmationAt;
		}

		/**
		 * Method returns timestamp of the last request for ack being sent.
		 * @return
		 */
		protected long getLastRequestSentAt() {
			return lastRequestSentAt;
		}

		/**
		 * Method checks if any ack was received or request for ack was sent since passed timestamp.
		 * @param since
		 * @return
		 */
		protected boolean gotAckOrSentRequestSince(long since) {
			return Math.max(lastConfirmationAt, lastRequestSentAt) > since;
		}

		/**
		 * Method returns no. of unacked stanzas since last request for ack was sent.
		 * @return
		 */
		protected int unackedSinceLastRequest() {
			int count = get();
			if (count >= lastRequestSentFor) {
				return count - lastRequestSentFor;
			} else {
				return count + (Integer.MAX_VALUE - lastRequestSentFor);
			}
		}

		public static class Entry {

			private final Packet packet;
			private final long stamp;

			public Entry(Packet packet) {
				this(packet, System::currentTimeMillis);
			}

			protected Entry(Packet packet, LongSupplier stampSupplier) {
				this.packet = packet;
				this.stamp = stampSupplier.getAsLong();
			}

			public Packet getPacketWithStamp() {
				Packet result = packet.copyElementOnly();
				if (result.getElemName() != Iq.ELEM_NAME && !result.isXMLNSStaticStr(DELAY_PATH, DELAY_XMLNS)) {
					String stamp = null;
					synchronized (formatter) {
						stamp = formatter.format(this.stamp);
					}
					String from = null;
					if (packet.getStanzaTo() != null) {
						from = packet.getStanzaTo().getDomain();
					} else if (packet.getPacketTo() != null) {
						from = packet.getPacketTo().getDomain();
					} else {
						// if we still do not have anything just set from to the cluster node name
						// (same as result.getPacket().getDomain())
						from = DNSResolverFactory.getInstance().getDefaultHost();
						if (log.isLoggable(Level.WARNING)) {
							log.log(Level.WARNING, "unacked packet without stanzaTo: {0}, and packetTo: {1}; setting from to: {2}; packet: {3} ",
									new Object[]{packet.getStanzaTo(), packet.getPacketTo(), from, packet.toString()});
						}
					}

					Element x = new Element("delay", new String[]{"from", "stamp", "xmlns"},
											new String[]{from, stamp, "urn:xmpp:delay"});
					Element carbon = result.getElement().findChild(e -> e.getXMLNS() == MessageCarbons.XMLNS);
					if (carbon == null) {
						result.getElement().addChild(x);
					} else {
						Element forwarded = carbon.getChild("forwarded", "urn:xmpp:forward:0");
						if (forwarded != null) {
							Element message = forwarded.getChild("message");
							if (message != null) {
								message.addChild(x);
							}
						}
					}
				}
				return result;
			}
		}
	}

	/**
	 * ResumptionTimeoutTask class is used for handing of timeout used during session resumption
	 */
	private class ResumptionTimeoutTask
			extends TimerTask {

		private final XMPPIOService service;

		public ResumptionTimeoutTask(XMPPIOService service) {
			this.service = service;
		}

		@Override
		public void cancel(boolean mayInterruptIfRunning) {
			if (!isCancelled()) {
				super.cancel(mayInterruptIfRunning);
			}
		}

		@Override
		public void run() {
			String id = (String) service.getSessionData().get(STREAM_ID_KEY);
			if (services.remove(id, service)) {
				//service.getSessionData().put(SERVICE_STOP_ALLOWED_KEY, true);
				service.clearWaitingPackets();
				connectionManager.serviceStopped(service);
				sendErrorsForQueuedPackets(service);
			}
		}

	}
}
