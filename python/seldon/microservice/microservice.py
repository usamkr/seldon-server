from flask import Flask
from concurrent import futures
import time
from seldon.microservice.predict import predict_blueprint
from seldon.microservice.recommend import recommend_blueprint
from seldon.microservice.extension import extension_blueprint
from seldon.microservice.rpc import RpcClassifier
from seldon.microservice.rpc import DefaultCustomDataHandler
import seldon
from sklearn.pipeline import Pipeline
import seldon.pipeline.util as sutl
import random
import pylibmc
from seldon.rpc import seldon_pb2
import grpc

_ONE_DAY_IN_SECONDS = 60 * 60 * 24

class Microservices(object):
    """
    Allow creation of predict and recommender microservices

    aws_key : str, optional
       aws key
    aws_secret : str, optional
       aws secret
    """
    def __init__(self,aws_key=None,aws_secret=None):
        self.aws_key = aws_key
        self.aws_secret = aws_secret

    def create_prediction_microservice(self,pipeline_folder,model_name):
        """
        Create a prediction Flask microservice app

        Parameters
        ----------

        pipeline_folder : str
           location of pipeline
        model_name : str
           model name to use for this pipeline
        """
        app = Flask(__name__)
                   
        rint = random.randint(1,999999)
        pw = sutl.PipelineWrapper(work_folder='/tmp/pl_'+str(rint),aws_key=self.aws_key,aws_secret=self.aws_secret)
        pipeline = pw.load_pipeline(pipeline_folder)
        
        app.config["seldon_pipeline_wrapper"] = pw
        app.config["seldon_pipeline"] = pipeline
        app.config["seldon_model_name"] = model_name
 
        app.register_blueprint(predict_blueprint)

        # other setup tasks
        return app

    def create_prediction_rpc_microservice(self,pipeline_folder,model_name,custom_data_handler=DefaultCustomDataHandler()):
        rint = random.randint(1,999999)
        pw = sutl.PipelineWrapper(work_folder='/tmp/pl_'+str(rint),aws_key=self.aws_key,aws_secret=self.aws_secret)
        pipeline = pw.load_pipeline(pipeline_folder)
        server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        seldon_pb2.add_SeldonServicer_to_server(RpcClassifier(pipeline,model_name,custom_data_handler), server)
        server.add_insecure_port('[::]:5000')
        server.start()
        try:
            while True:
                time.sleep(_ONE_DAY_IN_SECONDS)
        except KeyboardInterrupt:
            server.stop(0)



    def create_recommendation_microservice(self,recommender_folder,memcache_servers=None,memcache_pool_size=2):
        """
        create recommedation Flask microservice app

        Parameters
        ----------

        recommender_folder : str
           location of recommender model files
        memcache_servers : comma separated string, optional
           memcache server locations, e.g., 127.0.0.1:11211 
        memcache_pool_size : int, optional
           size of memcache pool
        """
        app = Flask(__name__)

        if not memcache_servers is None:
            mc = pylibmc.Client(memcache_servers)
            _mc_pool = pylibmc.ClientPool(mc, memcache_pool_size)
            app.config["seldon_memcache"] = _mc_pool
            
        if self.aws_key:
            rw = seldon.RecommenderWrapper(aws_key=self.aws_key,aws_secret=self.aws_secret)
        else:
            rw = seldon.RecommenderWrapper()
        recommender = rw.load_recommender(recommender_folder)
        app.config["seldon_recommender"] = recommender
 
        app.register_blueprint(recommend_blueprint)

        # other setup tasks
        return app


    def create_extension_microservice(self,extension_folder):
        """
        Create a prediction Flask microservice app

        Parameters
        ----------

        extension_folder : str
           location of extension
        """
        app = Flask(__name__)
                   
        rint = random.randint(1,999999)
        ew = seldon.ExtensionWrapper(work_folder='/tmp/pl_'+str(rint),aws_key=self.aws_key,aws_secret=self.aws_secret)
        extension = ew.load_extension(extension_folder)

        app.config["seldon_extension_wrapper"] = ew
        app.config["seldon_extension"] = extension
 
        app.register_blueprint(extension_blueprint)

        # other setup tasks
        return app


