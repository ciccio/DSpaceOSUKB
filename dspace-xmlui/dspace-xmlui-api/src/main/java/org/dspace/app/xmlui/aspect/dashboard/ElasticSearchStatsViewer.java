package org.dspace.app.xmlui.aspect.dashboard;

import edu.osu.library.dspace.statistics.ElasticSearchLogger;
import org.apache.cocoon.environment.ObjectModelHelper;
import org.apache.log4j.Logger;
import org.dspace.app.xmlui.aspect.statistics.ReportGenerator;
import org.dspace.app.xmlui.aspect.statistics.StatisticsTransformer;
import org.dspace.app.xmlui.cocoon.AbstractDSpaceTransformer;
import org.dspace.app.xmlui.utils.HandleUtil;
import org.dspace.app.xmlui.wing.WingException;
import org.dspace.app.xmlui.wing.element.*;
import org.dspace.content.*;
import org.dspace.core.Constants;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.facet.FacetBuilders;
import org.elasticsearch.search.facet.datehistogram.DateHistogramFacet;
import org.elasticsearch.search.facet.terms.TermsFacet;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: peterdietz
 * Date: 3/7/12
 * Time: 11:54 AM
 * To change this template use File | Settings | File Templates.
 */
public class ElasticSearchStatsViewer extends AbstractDSpaceTransformer {
    private static Logger log = Logger.getLogger(ElasticSearchStatsViewer.class);

    public void addPageMeta(PageMeta pageMeta) throws WingException {
        pageMeta.addMetadata("title").addContent("Elastic Search Data Display");
    }

    public void addBody(Body body) throws WingException, SQLException {
        Client client = ElasticSearchLogger.createElasticClient(false);
        try {
            //Try to find our dspace object
            DSpaceObject dso = HandleUtil.obtainHandle(objectModel);

            Division division = body.addDivision("elastic-stats");
            division.setHead("Elastic Data Display");
            division.addPara(dso.getType() + " " + dso.getName());

            ReportGenerator reportGenerator = new ReportGenerator();
            reportGenerator.addReportGeneratorForm(division, ObjectModelHelper.getRequest(objectModel));
            Date dateStart = reportGenerator.getDateStart();
            Date dateEnd = reportGenerator.getDateEnd();

            // Show some non-usage-stats.
            // @TODO Refactor the non-usage stats out of the StatsTransformer
            StatisticsTransformer statisticsTransformerInstance = new StatisticsTransformer(dateStart, dateEnd);

            // 1 - Number of Items in The Container (Community/Collection) (monthly and cumulative for the year)
            if(dso instanceof org.dspace.content.Collection || dso instanceof Community) {
                statisticsTransformerInstance.addItemsInContainer(dso, division);
            }

            // 2 - Number of Files in The Container (monthly and cumulative)
            if(dso instanceof org.dspace.content.Collection || dso instanceof Community) {
                statisticsTransformerInstance.addFilesInContainer(dso, division);
            }




            String owningObjectType = "";
            switch (dso.getType()) {
                case Constants.COLLECTION:
                    owningObjectType = "owningColl";
                    break;
                case Constants.COMMUNITY:
                    owningObjectType = "owningComm";
                    break;
            }

            TermQueryBuilder termQuery = QueryBuilders.termQuery(owningObjectType, dso.getID());


            SearchResponse resp = client.prepareSearch(ElasticSearchLogger.indexName)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .setQuery(termQuery)
                    .addFacet(FacetBuilders.termsFacet("types").field("type"))
                    .addFacet(FacetBuilders.termsFacet("top_bitstreams").field("id").facetFilter(FilterBuilders.termFilter("type", "bitstream")))
                    .addFacet(FacetBuilders.dateHistogramFacet("monthly_downloads").field("time").interval("month").facetFilter(FilterBuilders.termFilter("type", "bitstream")))
                    .execute()
                    .actionGet();


            //division.addPara(resp.toString());

            SearchHits hits = resp.getHits();
            int numberHits = (int) hits.totalHits();

            division.addPara("Querying bitstreams for elastic, Took " + resp.tookInMillis() + " ms to get " + numberHits + " hits.");

            if(numberHits == 0) {
                return;
            }

            // Number of File Downloads Per Month
            DateHistogramFacet monthlyDownloadsFacet = resp.getFacets().facet(DateHistogramFacet.class, "monthly_downloads");
            List<? extends DateHistogramFacet.Entry> monthlyDownloadsFacetEntries = monthlyDownloadsFacet.getEntries();
            Table monthlyDownloadsTable = division.addTable("monthly-downloads", monthlyDownloadsFacetEntries.size(), 10);
            monthlyDownloadsTable.setHead("Monthly Downloads Facet");
            Row monthlyDownloadsHeaderRow = monthlyDownloadsTable.addRow(Row.ROLE_DATA);
            monthlyDownloadsHeaderRow.addCell().addContent("Month/Date");
            monthlyDownloadsHeaderRow.addCell().addContent("Count");
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            for(DateHistogramFacet.Entry histogramEntry : monthlyDownloadsFacetEntries) {
                Row dataRow = monthlyDownloadsTable.addRow();
                Date facetDate = new Date(histogramEntry.getTime());
                dataRow.addCell().addContent(dateFormat.format(facetDate));
                dataRow.addCell().addContent("" + histogramEntry.getCount());
            }


            // Need to cast the facets to a TermsFacet so that we can get things like facet count. I think this is obscure.
            TermsFacet termsFacet = resp.getFacets().facet(TermsFacet.class, "types");
            List<? extends TermsFacet.Entry> termsFacetEntries = termsFacet.getEntries();

            Table facetTable = division.addTable("facettable", termsFacetEntries.size(), 10);
            facetTable.setHead("Facetting of Hits to this owningObject by resource type");
            Row facetTableHeaderRow = facetTable.addRow(Row.ROLE_HEADER);
            facetTableHeaderRow.addCell().addContent("Facet Name");
            facetTableHeaderRow.addCell().addContent("Count");

            for(TermsFacet.Entry facetEntry : termsFacetEntries) {
                Row row = facetTable.addRow();
                row.addCell().addContent(facetEntry.getTerm());
                row.addCell().addContent("" + facetEntry.getCount());
            }

            // Top Downloads to Owning Object
            TermsFacet bitstreamsFacet = resp.getFacets().facet(TermsFacet.class, "top_bitstreams");
            List<? extends TermsFacet.Entry> bitstreamFacetEntries = bitstreamsFacet.getEntries();

            Table topDownloadsTable = division.addTable("facet-bitstreams", bitstreamFacetEntries.size(), 10);
            topDownloadsTable.setHead("Facet of top downloads");
            Row topDownloadHeaderRow = topDownloadsTable.addRow(Row.ROLE_HEADER);
            topDownloadHeaderRow.addCell().addContent("Bitstream");
            topDownloadHeaderRow.addCell().addContent("Count");

            for(TermsFacet.Entry bitstreamEntry : bitstreamFacetEntries) {
                Row row = topDownloadsTable.addRow();
                Bitstream bitstream = Bitstream.find(context, Integer.parseInt(bitstreamEntry.getTerm()));
                row.addCell().addContent(bitstream.getName());
                row.addCell().addContent(bitstreamEntry.getCount());
            }


            Table table = division.addTable("datatable", numberHits, 18);
            table.setHead("Source Hits to this owning Objects");

            for(SearchHit hit : hits) {
                Map<String, Object> hitSource = hit.getSource();
                log.info(hitSource.size());
                Row thisRow = table.addRow();

                Set<Map.Entry<String, Object>> setHits = hitSource.entrySet();
                log.info("NumFields = "+setHits.size());

                Iterator fields = setHits.iterator();
                while(fields.hasNext()) {
                    Map.Entry thisEntry = (Map.Entry) fields.next();
                    Object key = thisEntry.getKey();
                    Object value = thisEntry.getValue();
                    thisRow.addCell().addContent(value.toString());
                }
            }



        } finally {
            client.close();
        }
    }
}