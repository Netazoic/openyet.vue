package com.netazoic.covid;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.netazoic.covid.ent.CTP_Daily;
import com.netazoic.covid.ent.JH_TimeSeries.JH_Column;
import com.netazoic.covid.ent.JH_TimeSeries.JH_TimeSeriesType;
import com.netazoic.covid.ent.JH_Global_Confirmed;
import com.netazoic.covid.ent.JH_Global_Deaths;
import com.netazoic.covid.ent.JH_Global_Recovered;
import com.netazoic.covid.ent.JH_US_Confirmed;
import com.netazoic.covid.ent.JH_US_Deaths;
import com.netazoic.covid.ent.ifDataType;
import com.netazoic.ent.ENTException;
import com.netazoic.ent.RouteAction;
import com.netazoic.ent.ServENT;
import com.netazoic.ent.ifDataSrc;
import com.netazoic.ent.ifDataSrcWrapper;
import com.netazoic.ent.ifDataSrcWrapper.MutableInt;
import com.netazoic.ent.ifDataSrcWrapper.RemoteDataRecordCtr;
import com.netazoic.ent.rdENT;
import com.netazoic.ent.rdENT.DataFmt;
import com.netazoic.ent.rdENT.SRC_ORG;
import com.netazoic.util.HttpUtil;
import com.netazoic.util.JSONUtil;
import com.netazoic.util.JsonObjectIterator;
import com.netazoic.util.RSObj;
import com.netazoic.util.RemoteDataObj;
import com.netazoic.util.SQLUtil;
import com.netazoic.util.ifRemoteDataObj;

@MultipartConfig
public class OpenYet extends ServENT {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	RouteAction homeHdlr = new HomeHdlr();

	private static final Logger logger = LogManager.getLogger(OpenYet.class);

	//creating enum HOME TemPLate?
	public  enum CVD_TP{
		Home("/Home/Home.hbs","Main home page"),
		AdminHome("/Home/AdminHome.hbs","Admin home page"),
		RetrieveData("/Data/RetrieveData.hbs", "Retrieve Data main page"),
		READ_ME("README.md","Todos/Redux Read Me"), 
		sql_GetCombinedData("/Data/sql/Combined/GetCombinedData.sql", "Get combined covid19 data"),
		sql_UpdateCombinedCountryCodes("/Data/sql/Combined/UpdateCombinedCountryCodes.sql", "Update country codes in combined"),
		sql_UpdateCombinedStateCodes("/Data/sql/Combined/UpdateCombinedStateCodes.sql", "Update state codes to ANSI for US entries"), 
		sql_CreateCombinedIncreaseStats("/Data/sql/Combined/CreateIncreaseStats.sql", "Create values for confirmed increase etc"),
		sql_CreateCountryRollups("/Data/sql/CreateCountryRollups.sql","Create summary entries for countries that are broken out by state"),
		sql_CreateStateRollups("/Data/sql/CreateStateRollups.sql","Create summary entries for states that are broken out by county"),

		sql_GetCounties("/Data/sql/GetCountyList.sql","Select list of counties for a given state/country"), 
		sql_GetCountries("/Data/sql/GetCountryList.sql","Select list of countries with countrycodes"), 
		sql_GetStates("/Data/sql/GetStateList.sql","Select list of state names/codes"),
		sql_GetCountryStates("/Data/sql/GetCountryStateList.sql", "Select list of states for a country"),

		sql_GetOpenYetData("/Data/sql/OpenYet/GetOpenYet.sql","Get data for the Open Yet page"),
		sql_GetDeathCorrelation("/Data/sql/GetDeathCorrelationToConfirmedCases.sql","Get number of deaths corresponding to reported confirmed cases from x days ago"),
	    
	    sql_GetNationalSummary("/Data/sql/OpenYet/GetNationalSummary.sql", "Get open-yet summary data at the national level"),
	    sql_GetStateSummary("/Data/sql/OpenYet/GetStateSummary.sql", "Get open-yet summary data at the state level"),
	    sql_GetCountySummary("/Data/sql/OpenYet/GetCountySummary.sql", "Get open-yet summary data a the county level"),
	    
		sql_GetRemoteDataStats("/Data/sql/GetRemoteDataStats.sql","Get stats on all remote data tables"), 
		;
		//Why store template path and description into variables?
		public String tPath;
		String desc;
		CVD_TP(String t, String d){
			tPath = t;
			desc = d;
		}
	}


	public enum CVD_DataCt{
		ctRemoteDataRecs, ctNewRemoteData, ctBadRecords
	}

	public enum CVD_Param{
		dataSrc, expireAll, expireExisting, country, state, sourceCode, lastUpdate, flgAdmin, PRD_MODE,DEV_MODE, countryCode, stateCode
	}

	public enum CVD_Route{
		home("/home","Show home page"),

		getCountryData("/cvd/getData/countries", "Get country table"),
		getOpenYet("/cvd/getData/getOpenYet","Get data for the 'Open Yet?' page"),
		getStateData("/cvd/getData/states","Get state table"),
		getCountyData("/cvd/getData/counties","Get counties list"),
		getCombinedData("/cvd/getData/combined", "Get combined covid19 data"),
		remoteDataStats("/cvd/remoteDataStats", "Get stats about remote data already retrieved"),
		// current summaries
		getNationalSummary("/cvd/getData/nationalSummary","Get current summary at the national level"),
		getStateSummary("/cvd/getData/stateSummary", "Get current summary at the state level for a given country"),
		getCountySummary("/cvd/getData/countySummary", "Get current summary at the county level for a given country/state")
		;

		public String route;
		public String desc;

		CVD_Route(String r, String d){
			route = r;
			desc = d;
		}
		public static CVD_Route getRoute(String rs) {
			for(CVD_Route r : CVD_Route.values()) {
				if(r.route.equals(rs)) return r;
			}
			return null;
		}

	}

	public enum CVD_DataSrc  implements ifDataSrc{
		JH_GLOBAL_CONF(JH_Global_Confirmed.class, JH_TimeSeriesType.confirmed,DataFmt.CSV,"Johns Hopkins time series new Confirmed", "JH_GLOBAL"),
		JH_GLOBAL_DEATHS(JH_Global_Deaths.class, JH_TimeSeriesType.dead,DataFmt.CSV,"Johns Hopkins time series new deaths", "JH_GLOBAL"),
		JH_GLOBAL_RECOVER(JH_Global_Recovered.class, JH_TimeSeriesType.recovered,DataFmt.CSV, "Johns Hopkins time series new recoveries", "JH_GLOBAL"),
		JH_US_CONF(JH_US_Confirmed.class, JH_TimeSeriesType.confirmed,DataFmt.CSV,"Johns Hopkins time series US new Confirmed", "JH_US"),
		JH_US_DEATHS(JH_US_Deaths.class, JH_TimeSeriesType.dead,DataFmt.CSV,"Johns Hopkins time series US new deaths", "JH_US"),
		//		JH_US_RECOVER(JH_US_Recovered.class, JH_TimeSeriesType.recovered,DataFmt.CSV, "Johns Hopkins time series US new recoveries"),
		CTP_STATES_DAILY( CTP_Daily.class, DataFmt.JSON,"Covid Tracking Project - States Daily", "CTP");

		public String srcCode;
		public String originCode;
		public ifDataType type;
		DataFmt dataFmt;
		Class<ifDataSrcWrapper> dswClass;
		public String desc;
		String urlBase = "https://raw.githubusercontent.com/CSSEGISandData/COVID-19/master/";
		public rdENT rdEnt;
		CVD_DataSrc( Class cl, DataFmt f, String d, String oc){
			this.dswClass = cl;
			this.dataFmt = f;
			this.desc = d;
			this.originCode = oc;
			this.srcCode = this.name();
			try {
				this.rdEnt = (rdENT) cl.newInstance();
			} catch (InstantiationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		CVD_DataSrc(Class cl, ifDataType t,DataFmt f, String d, String oc) {
			this.srcCode = this.name();
			this.dswClass = cl;
			this.dataFmt = f;
			this.type = t;
			this.desc = d;
			this.originCode = oc;

		}

		public rdENT getEnt() throws ENTException {
			if(this.rdEnt!=null) return this.rdEnt;
			try {
				this.rdEnt = (rdENT) this.dswClass.newInstance();
				return this.rdEnt;
			} catch (InstantiationException e) {
				throw new ENTException(e);
			} catch (IllegalAccessException e) {
				throw new ENTException(e);
			}
		}

		public String getURL() {
			return this.rdEnt.getDataURL();
		}

		static CVD_DataSrc getSrc(String tgt) {
			CVD_DataSrc dsrc;
			for (CVD_DataSrc src: CVD_DataSrc.values()) {
				if(src.name().equals(tgt)) return src;
			}
			return null;
		}
		@Override
		public DataFmt getFormat() {
			return this.dataFmt;
		}
		@Override
		public String getSrcCode() {
			return this.srcCode;
		}
		@Override
		public ifDataType getDataType() {
			return this.type;
		}

	}

	public OpenYet(){
		this.flgDebug = true;
	}




	@Override
	public void init(ServletConfig config) throws javax.servlet.ServletException {
		super.init(config);
		GetDataHdlr getData = new GetDataHdlr();
		defaultRoute = CVD_Route.home.route;
		routeMap.put(CVD_Route.home.route, homeHdlr);
		routeMap.put(CVD_Route.getOpenYet.route, new OpenYetDataHdlr());
		
		routeMap.put(CVD_Route.getCountryData.route, getData);
		routeMap.put(CVD_Route.getCountyData.route, getData);
		routeMap.put(CVD_Route.getStateData.route, getData);
		routeMap.put(CVD_Route.getCombinedData.route, getData);
		routeMap.put(CVD_Route.getNationalSummary.route, getData);
		routeMap.put(CVD_Route.getStateSummary.route, getData);
		routeMap.put(CVD_Route.getCountySummary.route, getData);
		
		routeMap.put(CVD_Route.remoteDataStats.route, new RemoteDataStats());
	}

	public RemoteDataObj getRDO(rdENT ds, Connection con) throws Exception {
		RemoteDataObj rdo = new RemoteDataObj();

		rdo.rdEnt = ds;
		rdo.con = con;
		rdo.mgr = this;
		rdo.init();
		return rdo;
	}

	

	private void importJSONData(ifRemoteDataObj rmdObj, RemoteDataRecordCtr ctrObj, Logger logger, Savepoint savePt, Connection con, InputStream is)
			throws SQLException, IOException {
		HashMap<String, Object> recMap;
		boolean flgCreate;
		JsonObjectIterator jsonItr = new JsonObjectIterator(is);
		String msgInfo;

		while(jsonItr.hasNext()){
			ctrObj.ctTotalRecords.increment();
			recMap = jsonItr.next();
			try{
				flgCreate = rmdObj.createRemoteDataRecord(recMap,con);
				if(flgCreate) ctrObj.ctNewRecordsCreated.increment();
				msgInfo = "Processed remote record: " + recMap.toString();

				logger.debug(msgInfo);
				if(ctrObj.ctTotalRecords.value%100 == 0){
					logger.info(msgInfo);
					logger.info(ctrObj.ctTotalRecords.value + " records processed.");
				}
				savePt = con.setSavepoint();
			}catch(Exception ex){
				ctrObj.ctBadRecords.increment();;
				logger.error(ex.getMessage());
				con.rollback(savePt);
			}
		}
		jsonItr.close();
	}

	public String reportImportStats(RemoteDataRecordCtr ctrObj){

		Integer ctRemoteDataRecs = ctrObj.ctTotalRecords.value;
		Integer ctNewRemoteData = ctrObj.ctNewRecordsCreated.value;
		Integer ctBadRecords = ctrObj.ctBadRecords.value;
		String msg ="Finished importing module records.\r\n";
		msg += "Processed " + ctRemoteDataRecs + " records.\r\n";
		//		       if(ctDuplicate > 0) msg += "Found " + ctDuplicate + " duplicate entries.\r\n";
		if(ctBadRecords > 0) msg += "Found " + ctBadRecords + " invalid records in the input.\r\n";
		if(ctRemoteDataRecs > 0) msg += "A total of " + ctRemoteDataRecs + " module records retrieved from remote source.\r\n";
		if(ctNewRemoteData > 0) msg += "Created " + ctNewRemoteData + " new module records\r\n";
		//        if(ctReturningRemoteData > 0) msg += "Found " + ctReturningRemoteData + " existing records \r\n";
		//        if(ctUpdatedRemoteData > 0) msg += "Updated " + ctUpdatedRemoteData + " existing records \r\n";
		//
		//        if(flgVerbose) System.out.println(msg);
		logger.info(msg);
		return msg;
	}

	


	private void shutDown(){
		/*
        if(psSelectRemoteDataID!=null) try{psSelectRemoteDataID.close();psSelectRemoteDataID=null;}catch(Exception ex){}
        if(psInsertRemoteData!=null) try{psInsertRemoteData.close();psInsertRemoteData=null;}catch(Exception ex){}
        if(psSelectRemoteData!=null) try{psSelectRemoteData.close();psSelectRemoteData=null;}catch(Exception ex){}
        if(deleteRemoteData!=null) try{deleteRemoteData.close();deleteRemoteData=null;}catch(Exception ex){}
        if(updateRemoteData!=null) try{updateRemoteData.close();updateRemoteData=null;}catch(Exception ex){}
		 */
	}
	public class OpenYetDataHdlr extends RouteEO{
		// Dedicated data getter for OpenYet data
		@Override
		public void routeAction(HttpServletRequest request, HttpServletResponse response, Connection con,
				HttpSession session) throws IOException, Exception {
			String routeString = getRoutePrimary(request);
			String q, tp,json;
			RSObj rso = null;
			Statement stat = null;
			try {
				rso = getOpenYetData(requestMap,con);
				
			}catch(Exception ex) {
				logger.debug(ex.getMessage());
				throw ex;
			}
			json = JSONUtil.toJSON(rso);
			ajaxResponse(json,response);
		}

		private RSObj getOpenYetData(HashMap<String, Object> requestMap, Connection con) throws Exception {
			String tp = CVD_TP.sql_GetOpenYetData.tPath;
			String q = parseQuery(tp,requestMap);
			logger.debug("q for OpenYet: " + q);
			RSObj rso = RSObj.getRSObj(q, "countrycode", con);
			logger.debug("Found " + rso.numRows + " records.");
			return rso;
		}
		
		
	}

	public class GetDataHdlr extends RouteEO{
		// Retrieve and return data from application DB
		@Override
		public void routeAction(HttpServletRequest request,
				HttpServletResponse response, Connection con, HttpSession session)
						throws IOException, Exception {
			String routeString = getRoutePrimary(request);
			String q, tp,json;
			RSObj rso;
			Statement stat = null;
			try {

				CVD_Route rte = CVD_Route.getRoute(routeString);
				switch(rte) {
				case getCountryData:
					tp = CVD_TP.sql_GetCountries.tPath;
					q = parser.parseQuery(tp, requestMap);
					rso = RSObj.getRSObj(q, "countrycode", con);
					break;
				case getStateData:
					tp = CVD_TP.sql_GetStates.tPath;
					q = parser.parseQuery(tp, requestMap);
					rso = RSObj.getRSObj(q, "code", con);
					break;
				case getCountyData:
					tp = CVD_TP.sql_GetCounties.tPath;
					q = parser.parseQuery(tp, requestMap);
					rso = RSObj.getRSObj(q, "county",con);
					break;
				case getCombinedData:
					tp = CVD_TP.sql_GetCombinedData.tPath;
					q  = parser.parseQuery(tp,requestMap);
					rso = RSObj.getRSObj(q, "countrycode", con);
					int limitIdx = q.lastIndexOf("LIMIT");
					if(limitIdx > 0) q = q.substring(0, limitIdx);
					q = "SELECT COUNT(country) as ct FROM (" + q + ")vc";
					stat = con.createStatement();
					ResultSet rs   = SQLUtil.execSQL(q, stat);
					rs.next();
					int ct = rs.getInt(1);
					rso.numRows = ct;
					break;
				case getNationalSummary:
					tp = CVD_TP.sql_GetNationalSummary.tPath;
					q = parseQuery(tp,requestMap);
					rso = RSObj.getRSObj(q, "countrycode", con);
					break;
				case getStateSummary:
					tp = CVD_TP.sql_GetStateSummary.tPath;
					q = parseQuery(tp,requestMap);
					rso = RSObj.getRSObj(q, "statecode", con);
					break;
				case getCountySummary:
					tp = CVD_TP.sql_GetCountySummary.tPath;
					q = parseQuery(tp,requestMap);
					rso = RSObj.getRSObj(q, "county", con);
					break;
				default:
					throw new Exception("Invalid data query");
				}

				json = JSONUtil.toJSON(rso);
				ajaxResponse(json,response);
			}catch(Exception ex) {
				throw ex;
			}finally {
				if(stat!=null)try {stat.close(); stat = null;}catch(Exception ex) {}
			}
		}
	}
	



	public class HomeHdlr extends RouteEO{

		@Override
		public void routeAction(HttpServletRequest request,
				HttpServletResponse response, Connection con, HttpSession session)
						throws IOException, Exception {
			String tPath = CVD_TP.Home.tPath;
			Map<String,Object> map = new HashMap<String,Object>();
			Map settings = getSettings();
			String PRD_MODE = (String) settings.get(CVD_Param.PRD_MODE.name());
			String DEV_MODE = (String) settings.get(CVD_Param.DEV_MODE.name());

			if(DEV_MODE != null) map.put(CVD_Param.DEV_MODE.name(), DEV_MODE);
			parseOutput(map, tPath, response);
		}	
	}

	public class RemoteDataStats extends RouteEO{

		@Override
		public void routeAction(HttpServletRequest request, HttpServletResponse response, Connection con,
				HttpSession session) throws IOException, Exception {
			HashMap<String,Object> map = new HashMap<String, Object>();
			String tp = CVD_TP.sql_GetRemoteDataStats.tPath;
			try {
				String q = parser.parseQuery(tp, map);
				RSObj rso = RSObj.getRSObj(q, "datasrccode",con);
				String json = getJSON(rso);
				ajaxResponse(json,response);
			}
			catch(Exception ex) {
				ajaxError(ex,response);
			}

		}

	}
	



}
