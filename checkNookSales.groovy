@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.2' )

import groovy.sql.Sql

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.POST
import static groovyx.net.http.ContentType.XML
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.ContentType.URLENC

import java.text.SimpleDateFormat

import org.apache.http.client.CookieStore
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.protocol.HttpContext
import org.apache.http.protocol.BasicHttpContext

import org.apache.http.client.protocol.ClientContext

public final int UNITS_SOLD = 0
public final int APPLICATION_NAME = 1;

def config = new ConfigSlurper().parse(new File("nook_sales_checker.properties").toURI().toURL())

def sql = Sql.newInstance(config.jdbcUrl, config.dbUsername, config.dbPassword, config.jdbcDriver)

def emails = []
if(args.size() == 0) {
    if(config.notificationEmails) {
        emails = config.notificationEmails.split(",")
    } else {
        println "Either set a notification email (or emails separated by commas) in the nook_sales_checker.properties file or pass it in as an argument"
        return
    }
} else if(args[0].indexOf(",") >= 0) {
    emails = config.notificationEmails.split(",")
} else {
    emails.add(args[0])
}

// Create a local instance of cookie store
CookieStore cookieStore = new BasicCookieStore()

def secret = ""
def returnValue = ""
def session = ""
def http = new HTTPBuilder('https://nookdeveloper.barnesandnoble.com')
http.client.cookieStore = cookieStore
http.request(GET,TEXT) { req ->
    uri.path = '/sign-in.html'
    headers.'User-Agent' = 'Mozilla/5.0 (Windows NT 6.1; WOW64; rv:14.0) Gecko/20100101 Firefox/14.0.1'
    response.success = { resp, reader ->
        assert resp.status == 200
        reader.eachLine { line ->
            if(line.contains("hidden") && line.contains('value="1"')) {
                secret = line.split("name=\"")[1].split("\"")[0]
            } else if(line.contains("return")) {
                returnValue = line.split("value=\"")[1].split("\"")[0]
            }
        }
    }
}

http.request(POST) {
    uri.path = '/sign-in.html'
    headers.'User-Agent' = 'Mozilla/5.0 (Windows NT 6.1; WOW64; rv:14.0) Gecko/20100101 Firefox/14.0.1'
    requestContentType = URLENC
    body = [username:config.nookAccountUsername, passwd:config.nookAccountPassword, submit:'Login', option:'com_user', task:'login', "return":"${returnValue}", "${secret}":'1']
    response.success = { resp, reader ->
    }
}

http.request(GET) {
    uri.path = '/api/session_key'
    headers.'User-Agent' = 'Mozilla/5.0 (Windows NT 6.1; WOW64; rv:14.0) Gecko/20100101 Firefox/14.0.1'
    headers.'Accept' = 'application/xml'
    contentType = TEXT
    response.success = { resp, xml ->
        assert resp.status == 200
        xml.eachLine { line ->
            if(line.contains("session")) {
                session = line.split("<session>")[1].split("<")[0]
            }
        }
    }
}

http.request(POST) {
    uri.path = '/api/application_list'
    headers.'User-Agent' = 'Mozilla/5.0 (Windows NT 6.1; WOW64; rv:14.0) Gecko/20100101 Firefox/14.0.1'
    headers.'Accept' = 'application/xml'
    contentType = XML
    requestContentType = URLENC
    body = [pversions:"0", session:"${session}"]
    response.success = { resp, xml ->
        assert resp.status == 200
        xml.data.application.each { application ->
            def row = sql.firstRow("SELECT * FROM application WHERE id = ${application.id.text()}")
            if(!row) {
                //application hasn't been saved - save it
                sql.execute("INSERT INTO application(id, application_status, version_status, build_flag, ean, name, bn_version, version, price) " + 
                                "VALUES(${application.id.text()}, ${application.application_status.text()}, ${application.version_status.text()}, ${application.build_flag.text()}, " + 
                                "${application.ean.text()}, '${application.name.text()}', ${application.bn_version.text()}, ${application.version.text()}, ${application.price.text()})")
            }
        }
    }
}

http.request(POST) {
    uri.path = '/api/report_data/2012/9'
    headers.'User-Agent' = 'Mozilla/5.0 (Windows NT 6.1; WOW64; rv:14.0) Gecko/20100101 Firefox/14.0.1'
    headers.'Accept' = 'application/xml'
    contentType = XML
    requestContentType = URLENC
    body = [session:"${session}"]
    response.success = { resp, xml ->
        assert resp.status == 200
        SimpleDateFormat df = new SimpleDateFormat("MM/dd/yyyy")
        def dailySales = [:]
        def saleSummary = ""
        xml.data.ArrayOfSalesRecord.SalesRecord.each { record ->
            def date = df.parse(record.DateOfSale.text())
                def title
                if(record.Title.text()) {
                    title = record.Title.text()
                }
                def displayIsbn
                if(record.DisplayISBN.text()) {
                    displayIsbn = record.DisplayISBN.text()
                    }
                def dailySaleData = dailySales[date]
                if(!dailySaleData) {
                    dailySaleData = [:]
                    dailySales[date] = dailySaleData
                }
                def saleDate = new java.sql.Timestamp(df.parse(record.DateOfSale.text()).getTime())
                def dailyAppRecordSale = sql.firstRow("SELECT units_sold, name FROM sales_record sr, application a " + 
                                                          "WHERE date_of_sale = '${saleDate}' AND isbn_ean = '${record.ISBN_EAN.text()}' " +
                                                        "AND a.ean = sr.isbn_ean")
                if(dailyAppRecordSale) {
                    //we already have statistics for this day - check if things have changed
                    if(dailyAppRecordSale[UNITS_SOLD] < Integer.parseInt(record.UnitsSold.text())) {
                        sql.execute("UPDATE sales_record SET net_unit_sold = ${Integer.parseInt(record.NetUnitSold.text())}, total_vendor_revenue = ${Double.parseDouble(record.TotalVendorRevenue.text())}, " +
                                "units_sold = ${Integer.parseInt(record.UnitsSold.text())}, units_returned = ${Integer.parseInt(record.UnitsReturned.text())} " + 
                                "WHERE date_of_sale = ${saleDate} and isbn_ean = ${record.ISBN_EAN.text()}")
                        saleSummary += "${dailyAppRecordSale[APPLICATION_NAME]}: Updated sales from ${dailyAppRecordSale[UNITS_SOLD]} to ${record.UnitsSold.text()}\n"
                    }
                } else {
                    //we don't have statistics for this day - insert them
                    def application = sql.firstRow("SELECT id, name FROM application WHERE ean = ${record.ISBN_EAN.text()}")
                    sql.execute("INSERT INTO sales_record(vendor_number, month, year, isbn_ean, title, list_price, vendor_revenue_per_unit, " +
                            "net_unit_sold, total_vendor_revenue, display_isbn, date_of_sale, units_sold, units_returned) " +
                            "VALUES('${record.VendorNumber.text()}', ${record.Month.text()}, ${record.Year.text()}, ${record.ISBN_EAN.text()}, " +
                            "${title}, ${record.ListPrice.text()}, ${record.VendorRevenuePerUnit.text()}, ${record.NetUnitSold.text()}, " + 
                            "${record.TotalVendorRevenue.text()}, ${displayIsbn}, '${saleDate}', " + 
                            "${record.UnitsSold.text()}, ${record.UnitsReturned.text()})")
                    saleSummary += "${application[APPLICATION_NAME]}: New sales ${record.UnitsSold.text()}\n"
                }
            }
            if(saleSummary) {
                emails.each { email ->
                    println "Email ${email}"
                    def ant = new AntBuilder()
                    ant.mail(mailhost:config.smtpServer, mailport:config.smtpPort, subject:config.mailSubject) {
                        from(address:config.fromAddress)
                        to(address:email)
                        message(saleSummary)
                    }
                }
            }
        }
}