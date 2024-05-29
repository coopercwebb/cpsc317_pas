package ca.ubc.cs.cs317.dnslookup;

import java.io.IOException;
import java.net.*;
import java.util.*;

public class DNSLookupService {

  public static final int DEFAULT_DNS_PORT = 53;
  private static final int MAX_INDIRECTION_LEVEL_NS = 10;
  private static final int MAX_QUERY_ATTEMPTS = 3;
  private static final int MAX_DNS_MESSAGE_LENGTH = 512;
  private static final int MAX_EDNS_MESSAGE_LENGTH = 1280;
  private static final int SO_TIMEOUT = 5000;

  private final DNSCache cache = DNSCache.getInstance();
  private final Random random = new Random();
  private final DNSVerbosePrinter verbose;
  private final DatagramSocket socket;

  /**
   * Creates a new lookup service. Also initializes the datagram socket object with a default timeout.
   *
   * @param verbose A DNSVerbosePrinter listener object with methods to be called at key events in the query
   *                processing.
   * @throws SocketException      If a DatagramSocket cannot be created.
   * @throws UnknownHostException If the nameserver is not a valid server.
   */
  public DNSLookupService(DNSVerbosePrinter verbose) throws SocketException, UnknownHostException {
    this.verbose = verbose;
    socket = new DatagramSocket();
    socket.setSoTimeout(SO_TIMEOUT);
  }

  /**
   * Closes the lookup service and related sockets and resources.
   */
  public void close() {
    socket.close();
  }

  /**
   * Examines a set of resource records to see if any of them are an answer to the given question.
   *
   * @param rrs      The set of resource records to be examined
   * @param question The DNS question
   * @return true if the collection of resource records contains an answer to the given question.
   */
  private boolean containsAnswer(Collection<CommonResourceRecord> rrs, DNSQuestion question) {
    for (CommonResourceRecord rr : rrs) {
      if (rr.getQuestion().equals(question) && rr.getRecordType() == question.getRecordType()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Finds all the results for a specific question. If there are valid (not expired) results in the cache, uses these
   * results, otherwise queries the nameserver for new records. If there are CNAME records associated to the question,
   * they are retrieved recursively for new records of the same type, and the returning set will contain both the
   * CNAME record and the resulting resource records of the indicated type.
   *
   * @param question             Host and record type to be used for search.
   * @param maxIndirectionLevels Number of CNAME indirection levels to support.
   * @return A set of resource records corresponding to the specific query requested.
   * @throws DNSErrorException If the number CNAME redirection levels exceeds the value set in
   *                           maxIndirectionLevels.
   */
  public Collection<CommonResourceRecord> getResultsFollowingCNames(DNSQuestion question, int maxIndirectionLevels)
    throws DNSErrorException {

    if (maxIndirectionLevels < 0) throw new DNSErrorException("CNAME indirection limit exceeded");

    Collection<CommonResourceRecord> directResults = iterativeQuery(question);
    if (containsAnswer(directResults, question)) {
      return directResults;
    }

    Set<CommonResourceRecord> newResults = new HashSet<>();
    for (CommonResourceRecord record : directResults) {
      newResults.add(record);
      if (record.getRecordType() == RecordType.CNAME) {
        newResults.addAll(getResultsFollowingCNames(
          new DNSQuestion(record.getTextResult(), question.getRecordType(), question.getRecordClass()),
          maxIndirectionLevels - 1));
      }
    }
    return newResults;
  }

  /**
   * Answers one question.  If there are valid (not expired) results in the cache, returns these results.
   * Otherwise it chooses the best nameserver to query, retrieves results from that server
   * (using individualQueryProcess which adds all the results to the cache) and repeats until either:
   * the cache contains an answer to the query, or
   * the cache contains an answer to the query that is a CNAME record rather than the requested type, or
   * every "best" nameserver in the cache has already been tried.
   *
   * @param question Host name and record type/class to be used for the query.
   */
  public Collection<CommonResourceRecord> iterativeQuery(DNSQuestion question)
    throws DNSErrorException {
    Set<CommonResourceRecord> ans = new HashSet<>();
    /* TODO: To be implemented by the student */

    // copy the question with CNAME instead of requested type, for exiting control flow when CNAME record found
    DNSQuestion question_CNAME = new DNSQuestion(question.getHostName(), RecordType.CNAME, question.getRecordClass());

    while (!containsAnswer(cache.getCachedResults(question), question) && !containsAnswer(cache.getCachedResults(question_CNAME), question_CNAME)) {
      List<CommonResourceRecord> best_nameservers = cache.getBestNameservers(question);
      List<CommonResourceRecord> best_nameservers_w_ip = cache.filterByKnownIPAddress(best_nameservers);
      boolean found_res = false;

      if (best_nameservers_w_ip.size() == 0) {
        // If no ip addresses are known for the nameservers, resolve a nameservers A record
        for (CommonResourceRecord ns : best_nameservers) {
          // Create a new iterative query with the dns server host name
          // If a result is returned, the ip address is now in the cache
          if (iterativeQuery(new DNSQuestion(ns.getTextResult(), RecordType.A, RecordClass.IN)).size() > 0) {
            found_res = true;
            break;
          }
        }
      }

      for (CommonResourceRecord record : best_nameservers_w_ip) {
        Set<ResourceRecord> ind_query_res = individualQueryProcess(question, record.getInetResult());
        if (ind_query_res != null) {
          // Valid ind_query_res, probe cache for results
          found_res = true;
          break;
        }


      }

      if (!found_res) {
        // No valid nameservers to query or no individual query result
        break;
      }
    }


    boolean resolved = false;
    if (!containsAnswer(cache.getCachedResults(question), question) && containsAnswer(cache.getCachedResults(question_CNAME), question_CNAME)) {
      // Contains CNAME answer, recursive call for CNAME resolution
      for (CommonResourceRecord curRec : cache.getCachedResults(question_CNAME)) {
        ans.add(curRec);
        if (!resolved) {
          DNSQuestion recursive_CNAME = new DNSQuestion(curRec.getTextResult(), question.getRecordType(), question.getRecordClass());
          Collection<CommonResourceRecord> cname_ans = iterativeQuery(recursive_CNAME);
          if (cname_ans.size() > 0) {
            resolved = true;
            ans.addAll(cname_ans);
          }
        }
      }
    }


    for (CommonResourceRecord curRec : cache.getCachedResults(question)) {
        if (curRec.getRecordType() == question.getRecordType() && curRec.getQuestion().equals(question)) {
            ans.add(curRec);
        }
    }


    return ans;
  }

  /**
   * Handles the process of sending an individual DNS query with a single question. Builds and sends the query (request)
   * message, then receives and parses the response. Received responses that do not match the requested transaction ID
   * are ignored. If no response is received after SO_TIMEOUT milliseconds, the request is sent again, with the same
   * transaction ID. The query should be sent at most MAX_QUERY_ATTEMPTS times, after which the function should return
   * without changing any values. If a response is received, all of its records are added to the cache.
   * <p>
   * If the reply contains a non-zero Rcode value, then throw a DNSErrorException.
   * <p>
   * The method verbose.printQueryToSend() must be called every time a new query message is about to be sent.
   *
   * @param question Host name and record type/class to be used for the query.
   * @param server   Address of the server to be used for the query.
   * @return If no response is received, returns null. Otherwise, returns a set of all resource records
   * received in the response.
   * @throws DNSErrorException if the Rcode in the response is non-zero
   */
  public Set<ResourceRecord> individualQueryProcess(DNSQuestion question, InetAddress server)
    throws DNSErrorException {
    /* TODO: To be implemented by the student */

    DNSMessage message = buildQuery(question);

    byte[] sendbuf = message.getUsed();
    byte[] recvbuf = new byte[MAX_DNS_MESSAGE_LENGTH];
    DatagramPacket sent_packet = new DatagramPacket(sendbuf, sendbuf.length, server, 53);
    DatagramPacket received_packet = new DatagramPacket(recvbuf, recvbuf.length);

    verbose.printQueryToSend("UDP", question, server, message.getID());

    DNSMessage received_message = null;

    boolean received = false;
    for (int attempt = 0; attempt < MAX_QUERY_ATTEMPTS; attempt++) {
      try {
        socket.send(sent_packet);
        socket.receive(received_packet);
        received_message = new DNSMessage(received_packet.getData(), received_packet.getLength());
        if (received_message.getID() != message.getID()) {
          // Ignore responses to different queries, and retry
        } else {
          received = true;
          break;
        }
      } catch (IOException e) {
          // Retry
      }
    }

    if (!received) {
      // Could not connect or receive valid message after MAX_QUERY_ATTEMPTS, return null
      return null;
    }

    // Potentially throws DNSErrorException if RCode is not 0
    return processResponse(received_message);
  }

  /**
   * Creates a DNSMessage containing a DNS query.
   * A random transaction ID must be generated and filled in the corresponding part of the query. The query
   * must be built as an iterative (non-recursive) request for a regular query with a single question. When the
   * function returns, the message's buffer's position (`message.buffer.position`) must be equivalent
   * to the size of the query data.
   *
   * @param question Host name and record type/class to be used for the query.
   * @return The DNSMessage containing the query.
   */
  public DNSMessage buildQuery(DNSQuestion question) {
    /* TODO: To be implemented by the student */

    // unsigned short
    short transaction_id = (short) random.nextInt(65535);

    DNSMessage message = new DNSMessage(transaction_id);

    // Single question
    message.addQuestion(question);

    return message;
  }

  /**
   * Parses and processes a response received by a nameserver.
   * If the reply contains a non-zero Rcode value, then throw a DNSErrorException.
   * Adds all resource records found in the response message to the cache.
   * Calls methods in the verbose object at appropriate points of the processing sequence. Must be able
   * to properly parse records of the types: A, AAAA, NS, CNAME and MX (the priority field for MX may be ignored). Any
   * other unsupported record type must create a record object with the data represented as a hex string (see method
   * byteArrayToHexString).
   *
   * @param message The DNSMessage received from the server.
   * @return A set of all resource records received in the response.
   * @throws DNSErrorException if the Rcode value in the reply header is non-zero
   */
  public Set<ResourceRecord> processResponse(DNSMessage message) throws DNSErrorException {
    /* TODO: To be implemented by the student */
    if (message.getRcode() != 0) {
      throw new DNSErrorException("RCode is " + message.getRcode());
    }

    Set<ResourceRecord> result = new HashSet();

    verbose.printResponseHeaderInfo(message.getID(), message.getAA(), message.getTC(), message.getRcode());

    for (int i = 0; i < message.getQDCount(); i++) {
      DNSQuestion curQuestion = message.getQuestion();
    }

    verbose.printAnswersHeader(message.getANCount());
    for (int i = 0; i < message.getANCount(); i++) {
      ResourceRecord curRR = message.getRR();
      verbose.printIndividualResourceRecord(curRR, curRR.getRecordType().getCode(), curRR.getRecordClassCode());
      cache.addResult((CommonResourceRecord) curRR);
      result.add(curRR);
    }

    verbose.printNameserversHeader(message.getNSCount());
    for (int i = 0; i < message.getNSCount(); i++) {
      ResourceRecord curRR = message.getRR();
      verbose.printIndividualResourceRecord(curRR, curRR.getRecordType().getCode(), curRR.getRecordClassCode());
      cache.addResult((CommonResourceRecord) curRR);
      result.add(curRR);
    }

    verbose.printAdditionalInfoHeader(message.getARCount());
    for (int i = 0; i < message.getARCount(); i++) {
      ResourceRecord curRR = message.getRR();
      verbose.printIndividualResourceRecord(curRR, curRR.getRecordType().getCode(), curRR.getRecordClassCode());
      cache.addResult((CommonResourceRecord) curRR);
      result.add(curRR);
    }

    return result;
  }

  public static class DNSErrorException extends Exception {
    public DNSErrorException(String msg) {
      super(msg);
    }
  }
}
