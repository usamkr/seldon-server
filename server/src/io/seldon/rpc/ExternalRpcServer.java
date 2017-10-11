package io.seldon.rpc;

import java.io.IOException;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.datanucleus.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import io.seldon.api.APIException;
import io.seldon.api.Constants;
import io.seldon.api.logging.PredictLogger;
import io.seldon.api.resource.ConsumerBean;
import io.seldon.api.rpc.ClassificationReply;
import io.seldon.api.rpc.ClassificationRequest;
import io.seldon.api.rpc.SeldonGrpc;
import io.seldon.api.service.ResourceServer;
import io.seldon.prediction.PredictionService;


@Component
public class ExternalRpcServer extends SeldonGrpc.SeldonImplBase implements ServerInterceptor {
	private static Logger logger = Logger.getLogger(ExternalRpcServer.class.getName());
	private static final int port = 5000;

	private final Server server;
	private final PredictionService predictionService;
	
	@Autowired
	private ResourceServer resourceServer;
	
	@Autowired
	PredictLogger predictLogger;
	
	final Metadata.Key<String> authKey = Metadata.Key.of(Constants.OAUTH_TOKEN,Metadata.ASCII_STRING_MARSHALLER);
	
	ThreadLocal<String> clientThreadLocal = new ThreadLocal<String>();	
	
	public static class SeldonServerCallListener<R> extends ForwardingServerCallListener<R>
	{
		ServerCall.Listener<R> delegate;
		ExternalRpcServer server;
		String client;
		
		public SeldonServerCallListener(ServerCall.Listener<R> delegate,String client,ExternalRpcServer server) {
			this.delegate = delegate;
			this.server = server;
			this.client = client;
		}
		
		@Override
		protected Listener<R> delegate() {
			return delegate;
		}
		
		@Override
		public void onMessage(R request) {
			server.clientThreadLocal.set(client);
		    super.onMessage(request);
		}
		
	}
	
	@Autowired
	public ExternalRpcServer(PredictionService predictionService)
	{
		logger.info("Initializing RPC server...");
		this.predictionService = predictionService;
		ServerBuilder<?> serverBuilder = ServerBuilder.forPort(port);
		server = serverBuilder.addService(ServerInterceptors.intercept(this, this)).build();
		
	}
	
	@PostConstruct
	public void startup(){
		logger.info("Starting RPC server");
		try
		{
			start();
		} catch (IOException e) {
			logger.error("Failed to start RPC server ",e);
		}
	}
	
	@Override
	public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,ServerCallHandler<ReqT, RespT> next) {
		logger.info("Call intercepted "+headers.toString());
		String token = headers.get(authKey);
		if (StringUtils.notEmpty(token))
		{
			try
			{
				logger.info("Token "+token);
				ConsumerBean consumer = resourceServer.validateResourceFromToken(token);
				logger.info("Setting call to client "+consumer.getShort_name());
				return new SeldonServerCallListener<ReqT>(next.startCall(call, headers),consumer.getShort_name(),this);
			}
			catch (APIException e)
			{
				logger.warn("API exception on getting token ",e);
				return next.startCall(call, headers);
			}
		}
		else
		{
			logger.warn("Empty token ignoring call");
			return next.startCall(call, headers);
		}
	}
	
	@Override
	public void classify(ClassificationRequest request, StreamObserver<ClassificationReply> responseObserver)
	{
		final String client = clientThreadLocal.get();
		if (StringUtils.notEmpty(client))
		{
			clientThreadLocal.set(null);
			ClassificationReply reply = predictionService.predict(client, request);
			responseObserver.onNext(reply);
			responseObserver.onCompleted();
			predictLogger.log(client, request, reply);
		}
		else
		{
			logger.info("Failed to get token");
			responseObserver.onError(new StatusException(io.grpc.Status.PERMISSION_DENIED.withDescription("Could not determine client from oauth_token")));
		}
	}
	
	 /** Start serving requests. */
	  public void start() throws IOException {
	    server.start();
	    logger.info("Server started");
	    Runtime.getRuntime().addShutdownHook(new Thread() {
	      @Override
	      public void run() 
	      {
	    	  logger.info("Shutting down");
	      }
	    });
	  }

	  /** Stop serving requests and shutdown resources. */
	  public void stop() {
	    if (server != null) {
	      server.shutdown();
	    }
	  }
	  


	  /**
	   * Await termination on the main thread since the grpc library uses daemon threads.
	   */
	  private void blockUntilShutdown() throws InterruptedException {
	    if (server != null) {
	      server.awaitTermination();
	    }
	}

	
	
}
