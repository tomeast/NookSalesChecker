NookSalesChecker
================

Groovy script to record and report on Nook app sales

Tired of the limited/slow app sales reports provided by Barnes and Noble?

Run this script periodically in order to pull down your sales data and email sales updates when new  sales occur.

Setup
1. Create application and sales_record tables in a database and specify the database details in the nook_sales_checker.properties file
 - see the included Postgres script, nook_sales.sql, for an example
2. Update the email and Nook account details in the nook_sales_checker.properties file
3. Run the script using groovy, ie groovy checkNookSales.groovy
4. Schedule it to run periodically
