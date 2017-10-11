/*
 * Seldon -- open source prediction engine
 * =======================================
 *
 * Copyright 2011-2015 Seldon Technologies Ltd and Rummble Ltd (http://www.seldon.io/)
 *
 * ********************************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ********************************************************************************************
 */

package io.seldon.recommendation.explanation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;

import org.apache.log4j.Logger;

import io.seldon.api.APIException;
import io.seldon.db.jdo.JDOFactory;

public class SqlExplanationProvider implements ExplanationProvider {

    private static Logger logger = Logger.getLogger(SqlExplanationProvider.class.getName());

    final private String clientName;
    private PersistenceManager pm;

    public SqlExplanationProvider(String clientName) {
        this.clientName = clientName;
        this.pm = null;
    }

    public PersistenceManager getPM() {
        if (pm == null) {
            pm = JDOFactory.get().getPersistenceManager(clientName);
            if (pm == null) {
                throw new APIException(APIException.INTERNAL_DB_ERROR);
            }
        }
        return pm;
    }

    @Override
    public String getExplanation(String recommender, String locale) {
        logger.debug(String.format("Gettting explanation from db for recommender[%s] locale[%s]", recommender, locale));

        String retVal = null;
        {
            String sql = "select re1.explanation as default_explanation,re2.explanation as explanation from recommendation_explanation re1 LEFT join recommendation_explanation re2 on re1.locale=re2.locale and re2.recommender=? where re1.locale=? and re1.recommender ='_DEFAULT_'";
            Query query = getPM().newQuery("javax.jdo.query.SQL", sql);
            List<Object> args = new ArrayList<>();
            args.add(recommender);
            args.add(locale);
            Collection<Object[]> results = (Collection<Object[]>) query.executeWithArray(args.toArray());
            if (results.size() != 1) {
                // Will return null
                logger.debug(String.format("Failed explanation from db for recommender[%s] locale[%s]", recommender, locale));
            } else {
                for (Object[] r : results) { // we just want the first item (there should be only 1)
                    String default_explanation = (String) r[0];
                    String explanation = (String) r[1];

                    if (explanation != null) {
                        retVal = explanation;
                        logger.debug(String.format("Retrieved explanation from db for recommender[%s] locale[%s] as[%s]", recommender, locale, retVal));
                    } else {
                        retVal = default_explanation;
                        logger.debug(String.format("Retrieved default_explanation from db for recommender[%s] locale[%s] as[%s]", recommender, locale, retVal));
                    }
                    break;
                }
            }
        }

        return retVal;
    }

    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }
}
