/*
 * Seldon -- open source prediction engine
 * =======================================
 * Copyright 2011-2015 Seldon Technologies Ltd and Rummble Ltd (http://www.seldon.io/)
 *
 **********************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at       
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ********************************************************************************************** 
*/
package io.seldon.external;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.seldon.api.APIException;
import io.seldon.api.rpc.ClassificationReply;
import io.seldon.api.rpc.ClassificationRequest;
import io.seldon.api.state.GlobalConfigHandler;
import io.seldon.api.state.GlobalConfigUpdateListener;
import io.seldon.clustering.recommender.RecommendationContext.OptionsHolder;
import io.seldon.prediction.PredictionAlgorithm;
import io.seldon.prediction.PredictionServiceResult;
import io.seldon.rpc.ClientRpcStore;


@Component
public class ExternalPredictionServer implements GlobalConfigUpdateListener, PredictionAlgorithm  {
	private static Logger logger = Logger.getLogger(ExternalPredictionServer.class.getName());
	private static final String name = ExternalPredictionServer.class.getName();
    private static final String URL_PROPERTY_NAME="io.seldon.algorithm.external.url";
    private static final String ALG_NAME_PROPERTY_NAME ="io.seldon.algorithm.external.name";
    private static final String ZK_CONFIG_TEMP = "prediction_server"; 
    private PoolingHttpClientConnectionManager cm;
    private CloseableHttpClient httpClient;
    ObjectMapper mapper = new ObjectMapper();
    
    private static final int DEFAULT_REQ_TIMEOUT = 200;
    private static final int DEFAULT_CON_TIMEOUT = 500;
    private static final int DEFAULT_SOCKET_TIMEOUT = 2000;
    
    private final ClientRpcStore rpcStore;
    
    public String getName()
    {
    	return name;
    }
    
    public static class PredictionServerConfig {
    	public int maxConnections;
    }
    
    @Autowired
    public ExternalPredictionServer(GlobalConfigHandler globalConfigHandler,ClientRpcStore rpcStore){
        cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(150);
        cm.setDefaultMaxPerRoute(150);
        
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(DEFAULT_REQ_TIMEOUT)
                .setConnectTimeout(DEFAULT_CON_TIMEOUT)
                .setSocketTimeout(DEFAULT_SOCKET_TIMEOUT).build();
        
        httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build();
        globalConfigHandler.addSubscriber(ZK_CONFIG_TEMP, this);
        this.rpcStore = rpcStore;
    }
    

	@Override
	public void configUpdated(String configKey, String configValue) {
		if (configValue != null && configValue.length() > 0)
		{
			ObjectMapper mapper = new ObjectMapper();
            try {
            	PredictionServerConfig config = mapper.readValue(configValue, PredictionServerConfig.class);
            	cm = new PoolingHttpClientConnectionManager();
                cm.setMaxTotal(config.maxConnections);
                cm.setDefaultMaxPerRoute(config.maxConnections);
                
                RequestConfig requestConfig = RequestConfig.custom()
                        .setConnectionRequestTimeout(DEFAULT_REQ_TIMEOUT)
                        .setConnectTimeout(DEFAULT_CON_TIMEOUT)
                        .setSocketTimeout(DEFAULT_SOCKET_TIMEOUT).build();
                

                httpClient = HttpClients.custom()
                        .setConnectionManager(cm)
                        .setDefaultRequestConfig(requestConfig)
                        .build();
                logger.info("Updated httpclient to use "+config.maxConnections+" max connections");
            } catch (Exception e) {
                throw new RuntimeException(String.format("* Error * parsing statsd configValue[%s]", configValue),e);
            }
		}
		
	}

	
    public JsonNode predict(String client, JsonNode jsonNode, OptionsHolder options) 
    {
    		long timeNow = System.currentTimeMillis();
    		URI uri = URI.create(options.getStringOption(URL_PROPERTY_NAME));
    		try {
    			URIBuilder builder = new URIBuilder().setScheme("http")
    					.setHost(uri.getHost())
    					.setPort(uri.getPort())
    					.setPath(uri.getPath())
    					.setParameter("client", client)
    					.setParameter("json", jsonNode.toString());

    			uri = builder.build();
    		} catch (URISyntaxException e) 
    		{
    			throw new APIException(APIException.GENERIC_ERROR);
    		}
    		HttpContext context = HttpClientContext.create();
    		HttpGet httpGet = new HttpGet(uri);
    		try  
    		{
    			if (logger.isDebugEnabled())
    				logger.debug("Requesting " + httpGet.getURI().toString());
    			CloseableHttpResponse resp = httpClient.execute(httpGet, context);
    			try
    			{
    				if(resp.getStatusLine().getStatusCode() == 200) 
    				{
    					ObjectMapper mapper = new ObjectMapper();
    				    JsonFactory factory = mapper.getFactory();
    				    JsonParser parser = factory.createParser(resp.getEntity().getContent());
    				    JsonNode actualObj = mapper.readTree(parser);
    				    
    				    return actualObj;
    				} 
    				else 
    				{
    					logger.error("Couldn't retrieve prediction from external prediction server -- bad http return code: " + resp.getStatusLine().getStatusCode());
    					throw new APIException(APIException.GENERIC_ERROR);
    				}
    			}
    			finally
    			{
    				if (resp != null)
    					resp.close();
    				if (logger.isDebugEnabled())
    					logger.debug("External prediction server took "+(System.currentTimeMillis()-timeNow) + "ms");
    			}
    		} 
    		catch (IOException e) 
    		{
    			logger.error("Couldn't retrieve prediction from external prediction server - ", e);
    			throw new APIException(APIException.GENERIC_ERROR);
    		}
    		catch (Exception e)
            {
    			logger.error("Couldn't retrieve prediction from external prediction server - ", e);
    			throw new APIException(APIException.GENERIC_ERROR);
            }
    		finally
    		{
    			
    		}

    }

    @Override
    public PredictionServiceResult predictFromJSON(String client, JsonNode jsonNode, OptionsHolder options) 
    {
    	try
    	{
    		JsonNode actualObj = predict(client, jsonNode, options);
    		ObjectMapper mapper = new ObjectMapper();
			ObjectReader reader = mapper.reader(PredictionServiceResult.class);
			PredictionServiceResult res = reader.readValue(actualObj);
			return res;
    		} catch (JsonProcessingException e) {
    			logger.error("Couldn't retrieve prediction from external prediction server - ", e);
    			throw new APIException(APIException.GENERIC_ERROR);
    		} catch (IOException e) {
    			logger.error("Couldn't retrieve prediction from external prediction server - ", e);
    			throw new APIException(APIException.GENERIC_ERROR);
    		}
    }

	@Override
	public ClassificationReply predictFromProto(String client, ClassificationRequest request, OptionsHolder options) 
	{
		JsonNode jsonNode = rpcStore.getJSONForRequest(client, request);
		JsonNode jsonReply = predict(client, jsonNode, options);
		return rpcStore.getPredictReplyFromJson(client, jsonReply);
	}

    
}
