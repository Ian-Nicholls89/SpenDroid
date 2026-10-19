"""Made-up statements in four styles, with the pages around them. No real data."""
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
W, H = A4

def footer(c, n, total):
    c.setFont("Helvetica", 7); c.drawString(60, 30, f"Page {n} of {total}"); c.drawRightString(W - 60, 30, "Example Card Services Ltd, registered in England 0000000")

def tc_page(c, n, total):
    c.setFont("Helvetica-Bold", 11); c.drawString(60, H - 70, "Important information about your account")
    c.setFont("Helvetica", 8)
    y = H - 95
    for line in [
        "If you make a purchase of £100.00 on 12 MAR and pay your balance in full by the due date, no interest is charged.",
        "Interest example: 01 APR 30 APR 25.00 would be charged on a balance of 1,500.00 at 22.9% APR.",
        "Minimum payment: the higher of £25.00 or 3% of your balance. Late fee 12.00.",
        "Date        Description                 Amount",
        "Your rights: you may cancel within 14 days. Call 0800 000 0000.",
    ] * 3:
        c.drawString(60, y, line); y -= 14
    footer(c, n, total); c.showPage()

def giro(c, n, total, amount):
    c.setFont("Helvetica-Bold", 10); c.drawString(60, 300, "bank giro credit")
    c.setFont("Helvetica", 9)
    c.drawString(60, 280, "Date ............  Cashier's stamp")
    c.drawString(60, 262, "Card number 0000 0000 0000 1234     Sort code 00-00-00   Account 00000000")
    c.drawRightString(W - 60, 262, f"£ {amount}")
    c.drawString(60, 244, "Amount paid £ ...........   Fee ........")
    footer(c, n, total); c.showPage()

# --- A: a credit card like the user's: trans/post date, reference, single Amount, "626.04 -", sub-totals, two cardholders.
def card_a():
    c = canvas.Canvas("/tmp/pdfimp/samples/a-card-subtotals.pdf", pagesize=A4)
    total = 5
    # cover
    c.setFont("Helvetica-Bold", 16); c.drawString(60, H - 80, "Example Credit Card statement")
    c.setFont("Helvetica", 10)
    c.drawString(60, H - 110, "Statement date 23 April 2026")
    rows = [("Previous balance", "£410.20"), ("Payments and credits", "£414.40"), ("New spending", "£288.28"),
            ("New balance", "£284.08"), ("Minimum payment", "£25.00"), ("Credit limit", "£3,000.00")]
    y = H - 150
    for k, v in rows:
        c.drawString(80, y, k); c.drawRightString(300, y, v); y -= 16
    c.drawString(60, y - 20, "Please pay by 17 May 2026.")
    footer(c, 1, total); c.showPage()
    # transactions page 1
    def header(c, y):
        c.setFont("Helvetica-Bold", 10)
        c.drawString(70, y + 12, "Trans"); c.drawString(110, y + 12, "Post")
        c.drawString(70, y, "Date"); c.drawString(110, y, "Date"); c.drawString(230, y, "Description"); c.drawRightString(W - 60, y, "Amount")
        c.setFont("Helvetica", 9)
    c.setFont("Helvetica-Bold", 10); c.drawString(70, H - 60, "24 March - 23 April 2026")
    y = H - 100; header(c, y); y -= 22
    c.setFont("Helvetica-Bold", 9); c.drawString(230, y, "BALANCE FROM PREVIOUS STATEMENT"); c.drawRightString(W - 60, y, "£410.20"); y -= 16
    c.setFont("Helvetica", 9)
    c.drawString(70, y, "17 APR 17 APR"); c.drawString(230, y, "DIRECT DEBIT PAYMENT"); c.drawRightString(W - 60, y, "410.20 -"); y -= 16
    c.drawString(330, y, "Sub-Total"); c.drawRightString(W - 60, y, "0.00"); y -= 30
    header(c, y); y -= 18
    c.drawString(70, y, "A N OTHER"); c.drawString(330, y, "Card ending in 1234"); y -= 16
    tx = [("27 MAR", "30 MAR", "11111111", "GROCER ONLINE       0300 000 0000ENG", "85.45"),
          ("30 MAR", "31 MAR", "22222222", "DELI KITCHEN LIMI   SALISBURY   ENG", "12.10"),
          ("31 MAR", "01 APR", "33333333", "CAR PARK CA009      SALISBURY", "0.40"),
          ("31 MAR", "01 APR", "33333334", "CAR PARK CA009      SALISBURY", "3.50"),
          ("02 APR", "03 APR", "44444444", "SumUp  *Animal Park  Gillingham", "20.50"),
          ("03 APR", "06 APR", "55555555", "BAKERY Plc          Amesbury", "9.15")]
    for t, p, r, d, a in tx:
        c.drawString(70, y, f"{t} {p}"); c.drawString(160, y, r); c.drawString(230, y, d); c.drawRightString(W - 60, y, a); y -= 16
    footer(c, 2, total); c.showPage()
    # transactions page 2 (continues, header repeated)
    y = H - 100; header(c, y); y -= 22
    tx2 = [("04 APR", "06 APR", "66666666", "PHONE SHOP CO UK    LONDON  LND", "45.00"),
           ("08 APR", "09 APR", "77777777", "GROCER ONLINE       0300 000 0000ENG", "87.68"),
           ("12 APR", "13 APR", "88888888", "SHOP REFUND         SALISBURY", "4.20 -"),
           ("15 APR", "16 APR", "99999999", "Hardware 1021       SALISBURY", "21.50"),
           ("18 APR", "20 APR", "12121212", "Arcade **Leisure L  Tylwch  GBR", "1.50"),
           ("18 APR", "20 APR", "12121213", "Arcade **Leisure L  Tylwch  GBR", "1.50")]
    for t, p, r, d, a in tx2:
        c.drawString(70, y, f"{t} {p}"); c.drawString(160, y, r); c.drawString(230, y, d); c.drawRightString(W - 60, y, a); y -= 16
    c.drawString(330, y, "Sub-Total"); c.drawRightString(W - 60, y, "284.08"); y -= 16
    # NB: 85.45+12.10+0.40+3.50+20.50+9.15+45.00+87.68-4.20+21.50+1.50+1.50 = 283.38? computed in test
    footer(c, 3, total); c.showPage()
    tc_page(c, 4, total); giro(c, 5, total, "284.08")
    c.save()

# --- B: a current account: date once per day, Paid out / Paid in / Balance, brought forward, wrapped descriptions.
def current_b():
    c = canvas.Canvas("/tmp/pdfimp/samples/b-current-balance.pdf", pagesize=A4)
    total = 3
    c.setFont("Helvetica-Bold", 14); c.drawString(60, H - 70, "Example Bank - Current account statement")
    c.setFont("Helvetica", 9); c.drawString(60, H - 90, "Period: 01/09/2026 to 30/09/2026      Sort code 00-00-00  Account 00000000")
    y = H - 130
    c.setFont("Helvetica-Bold", 9)
    c.drawString(60, y, "Date"); c.drawString(120, y, "Description"); c.drawRightString(390, y, "Paid out"); c.drawRightString(470, y, "Paid in"); c.drawRightString(W - 60, y, "Balance")
    c.setFont("Helvetica", 9); y -= 18
    rows = [("01 Sep", "BROUGHT FORWARD", None, None, "1,250.00"),
            ("01 Sep", "CARD PAYMENT TO CAFE ONE", "4.25", None, None),
            (None, "DIRECT DEBIT ENERGY CO", "92.00", None, "1,153.75"),
            ("05 Sep", "SALARY EXAMPLE EMPLOYER", None, "2,340.00", "3,493.75"),
            ("12 Sep", "STANDING ORDER TO SAVINGS", "200.00", None, None),
            (None, "CARD PAYMENT TO A VERY LONG MERCHANT NAME", "18.99", None, "3,274.76"),
            (None, "+ CONTINUED REFERENCE 1234", None, None, None),
            ("28 Sep", "REFUND FROM SHOP", None, "12.00", "3,286.76"),
            ("30 Sep", "CARRIED FORWARD", None, None, "3,286.76")]
    for d, desc, out, inn, bal in rows:
        if d: c.drawString(60, y, d)
        c.drawString(120, y, desc)
        if out: c.drawRightString(390, y, out)
        if inn: c.drawRightString(470, y, inn)
        if bal: c.drawRightString(W - 60, y, bal)
        y -= 15
    c.setFont("Helvetica", 7); c.drawString(60, 30, "Page 1 of 3"); c.showPage()
    tc_page(c, 2, total); giro(c, 3, total, "0.00")
    c.save()

# --- C: card with CR credits, dates with years, "Transaction details" and "Amount (£)".
def card_c():
    c = canvas.Canvas("/tmp/pdfimp/samples/c-card-cr.pdf", pagesize=A4)
    c.setFont("Helvetica", 9)
    c.drawString(60, H - 60, "Statement period 15 December 2025 to 14 January 2026")
    c.drawString(60, H - 75, "Opening balance £120.00    Closing balance £163.49")
    y = H - 110
    c.setFont("Helvetica-Bold", 9); c.drawString(60, y, "Date"); c.drawString(140, y, "Transaction details"); c.drawRightString(W - 60, y, "Amount (£)")
    c.setFont("Helvetica", 9); y -= 16
    for d, desc, a in [("20 Dec 25", "TOY SHOP LONDON", "45.99"), ("24 Dec 25", "PAYMENT RECEIVED - THANK YOU", "120.00CR"),
                       ("02 Jan 26", "BOOKSHOP", "17.50"), ("10 Jan 26", "TRAIN TICKETS", "100.00")]:
        c.drawString(60, y, d); c.drawString(140, y, desc); c.drawRightString(W - 60, y, a); y -= 15
    c.drawString(60, 30, "Page 1 of 1"); c.showPage(); c.save()

# --- D: an unfamiliar layout: odd headings, slash dates, money with £ and leading minus.
def odd_d():
    c = canvas.Canvas("/tmp/pdfimp/samples/d-odd-layout.pdf", pagesize=A4)
    c.setFont("Helvetica", 9); c.drawString(60, H - 60, "Your activity, March 2026")
    y = H - 100
    c.setFont("Helvetica-Bold", 9); c.drawString(60, y, "When"); c.drawString(140, y, "What"); c.drawRightString(W - 60, y, "How much")
    c.setFont("Helvetica", 9); y -= 16
    for d, desc, a in [("03/03/2026", "Coffee place", "-£3.20"), ("04/03/2026", "Wages", "£1,800.00"), ("09/03/2026", "Supermarket", "-£54.10")]:
        c.drawString(60, y, d); c.drawString(140, y, desc); c.drawRightString(W - 60, y, a); y -= 15
    c.showPage(); c.save()

card_a(); current_b(); card_c(); odd_d()
print("ok")

# --- E: the user's second layout, made up: summary box over several lines, giro on page 1, and an
# interest-rate table after NEW BALANCE that must not become transactions.
def card_e():
    c = canvas.Canvas("/tmp/pdfimp/samples/e-card-interest-table.pdf", pagesize=A4)
    c.setFont("Helvetica", 9)
    c.drawRightString(W - 60, H - 40, "1 of 3")
    c.drawString(60, H - 70, "Total Credit Limit"); c.drawRightString(300, H - 70, "£3,000")
    c.drawString(60, H - 85, "Account  23 June 2026")
    c.drawString(60, H - 110, "Balance brought forward from"); c.drawRightString(300, H - 117, "£500.00"); c.drawString(60, H - 124, "previous statement")
    c.drawString(60, H - 140, "Payments to your account"); c.drawRightString(300, H - 140, "£500.00 -")
    c.drawString(60, H - 155, "Spending on your account"); c.drawRightString(300, H - 162, "+ £61.75"); c.drawString(60, H - 169, "Fees and interest")
    c.setFont("Helvetica-Bold", 10); c.drawString(60, H - 190, "New Balance"); c.drawRightString(300, H - 190, "= £61.75")
    c.setFont("Helvetica", 9); c.drawString(60, H - 205, "Minimum Payment"); c.drawRightString(300, H - 205, "£25.00")
    c.drawString(60, H - 230, "If you only pay the minimum payment of £25.00 by the due date of 17 July 2026 it will take longer.")
    c.drawString(60, 300, "bank giro credit"); c.drawString(60, 285, "Paid in by"); c.drawString(60, 270, "Date")
    c.drawString(60, 255, "0000 0000 0000 1234"); c.drawString(60, 240, "Total cash"); c.drawString(60, 225, "Fee  Cheques  00-00-00  00  £")
    c.drawString(60, 200, "<0000000000001234<  000000+<  00  x")
    c.showPage()
    c.setFont("Helvetica", 9); c.drawRightString(W - 60, H - 40, "2 of 3")
    c.drawString(60, H - 70, "24 May - 23 June 2026")
    def header(y):
        c.setFont("Helvetica-Bold", 10)
        c.drawString(70, y + 12, "Trans"); c.drawString(110, y + 12, "Post")
        c.drawString(70, y, "Date"); c.drawString(110, y, "Date"); c.drawString(230, y, "Description"); c.drawRightString(W - 60, y, "Amount")
        c.setFont("Helvetica", 9)
    y = H - 100; header(y); y -= 22
    c.setFont("Helvetica-Bold", 9); c.drawString(230, y, "BALANCE FROM PREVIOUS STATEMENT"); c.drawRightString(W - 60, y, "£500.00"); y -= 16
    c.setFont("Helvetica", 9)
    c.drawString(70, y, "17 JUN 17 JUN"); c.drawString(230, y, "DIRECT DEBIT PAYMENT"); c.drawRightString(W - 60, y, "500.00 -"); y -= 16
    c.drawString(330, y, "Sub-Total"); c.drawRightString(W - 60, y, "0.00"); y -= 30
    header(y); y -= 18
    c.drawString(70, y, "A N OTHER"); c.drawString(330, y, "Card ending in 1234"); y -= 16
    for t, p, r, d, a in [("25 MAY", "26 MAY", "31313131", "CORNER SHOP  SALISBURY  GBR", "12.40"),
                          ("30 MAY", "02 JUN", "41414141", "Zettle_*Fish Van  Fordingbridge", "8.35")]:
        c.drawString(70, y, f"{t} {p}"); c.drawString(160, y, r); c.drawString(230, y, d); c.drawRightString(W - 60, y, a); y -= 16
    c.showPage()
    c.setFont("Helvetica", 9); c.drawRightString(W - 60, H - 40, "3 of 3")
    y = H - 100; header(y); y -= 22
    c.drawString(70, y, "20 JUN 22 JUN"); c.drawString(160, y, "51515151"); c.drawString(230, y, "GARDEN CENTRE  WILTON"); c.drawRightString(W - 60, y, "41.00"); y -= 16
    c.drawString(330, y, "Sub-Total"); c.drawRightString(W - 60, y, "61.75"); y -= 16
    c.setFont("Helvetica-Bold", 9); c.drawString(230, y, "NEW BALANCE"); c.drawRightString(W - 60, y, "£61.75"); y -= 30
    c.setFont("Helvetica", 8)
    c.drawString(70, y, "INTEREST RATES"); y -= 14
    c.drawString(70, y, "BALANCE  STANDARD  MONTHLY  INTEREST"); y -= 12
    c.drawString(70, y, "Interest rate  Interest charged  BALANCE"); y -= 14
    c.drawString(70, y, "Purchases"); c.drawString(250, y, "24.949%"); c.drawString(380, y, "£0.00"); c.drawRightString(W - 60, y, "£61.75"); y -= 14
    c.drawString(70, y, "Money transfers"); c.drawString(250, y, "29.949%"); c.drawString(380, y, "£0.00"); c.drawRightString(W - 60, y, "£0.00"); y -= 20
    c.drawString(70, y, "This year  Year TO DATE"); y -= 12
    c.drawString(70, y, "Interest charged so far"); c.drawString(380, y, "0"); c.drawRightString(W - 60, y, "0"); y -= 12
    c.drawString(70, y, "Total fees charged"); c.drawString(380, y, "0"); c.drawRightString(W - 60, y, "0")
    c.showPage(); c.save()

card_e()

# --- F: a current account laid out like First Direct's, made up: "£ Paid out" headings, a
# payment-type code, details over two lines with the amount on the last, the balance once a day,
# brought/carried forward on each page, and a summary box with Payments In and Out.
def current_f():
    c = canvas.Canvas("/tmp/pdfimp/samples/f-current-amount-last.pdf", pagesize=A4)
    def header(y):
        c.setFont("Helvetica-Bold", 9)
        c.drawString(60, y, "Date"); c.drawString(110, y, "Payment type and details")
        c.drawRightString(400, y, "£ Paid out"); c.drawRightString(470, y, "£ Paid in"); c.drawRightString(540, y, "£ Balance")
        c.setFont("Helvetica", 9)
    def row(y, date=None, code=None, details="", out=None, inn=None, bal=None):
        if date: c.drawString(60, y, date)
        if code: c.drawString(110, y, code)
        c.drawString(140, y, details)
        if out: c.drawRightString(400, y, out)
        if inn: c.drawRightString(470, y, inn)
        if bal: c.drawRightString(540, y, bal)
    c.setFont("Helvetica", 9)
    c.drawString(60, H - 50, "examplebank.com"); c.drawString(60, H - 62, "03 000 000 000")
    c.drawString(60, H - 90, "A N OTHER"); c.drawString(60, H - 102, "1 Example Road"); c.drawString(60, H - 114, "TOWN AB1 2CD")
    c.drawString(330, H - 90, "Your Statement")
    c.drawString(330, H - 110, "Opening Balance"); c.drawRightString(540, H - 110, "£1,250.00")
    c.drawString(330, H - 124, "Payments In"); c.drawRightString(540, H - 124, "£2,340.00")
    c.drawString(330, H - 138, "Payments Out"); c.drawRightString(540, H - 138, "£415.26")
    c.drawString(330, H - 152, "Closing Balance"); c.drawRightString(540, H - 152, "£3,174.74")
    c.drawString(330, H - 166, "Arranged Overdraft Limit"); c.drawRightString(540, H - 166, "£250.00")
    c.drawString(60, H - 190, "29 September to 28 October 2026   Account Statement")
    c.drawString(60, H - 210, "Account Name   Sortcode   Account Number   Sheet Number")
    c.drawString(60, H - 222, "MR A N OTHER   00-00-00   00000000   123")
    y = H - 250; header(y); y -= 18
    row(y, "29 Sep 26", None, "BALANCE BROUGHT FORWARD", bal="1,250.00"); y -= 14
    row(y, "29 Sep 26", "DD", "ENERGY COMPANY LTD"); y -= 12
    row(y, details="GAS PAYMENT", out="92.00"); y -= 12
    row(y, None, "DD", "NEXTCARD PLC"); y -= 12
    row(y, details="Credit Card", out="60.04", bal="1,097.96"); y -= 14
    row(y, "30 Sep 26", ")))", "THE CORNER CAFE"); y -= 12
    row(y, details="SALISBURY", out="4.25", bal="1,093.71"); y -= 14
    row(y, "01 Oct 26", "CR", "EXAMPLE EMPLOYER", inn="2,340.00"); y -= 12
    row(y, None, "VIS", "GARDEN SHOP"); y -= 12
    row(y, details="123456789", out="18.99"); y -= 12
    row(y, None, ")))", "BAKERY Plc"); y -= 12
    row(y, details="Amesbury", out="2.98", bal="3,411.74"); y -= 14
    row(y, details="Balance carried forward", bal="3,411.74"); y -= 20
    c.setFont("Helvetica", 7); c.drawString(60, 40, "Example Bank is a division of Example Bank UK plc"); c.showPage()
    c.setFont("Helvetica", 9)
    c.drawString(60, H - 50, "examplebank.com"); c.drawString(60, H - 70, "29 September to 28 October 2026")
    y = H - 100; header(y); y -= 18
    row(y, details="Balance brought forward", bal="3,411.74"); y -= 14
    row(y, "05 Oct 26", "SO", "SAVINGS ACCOUNT"); y -= 12
    row(y, details="TRANSFER", out="200.00"); y -= 12
    row(y, None, ")))", "CAR PARK CA009"); y -= 12
    row(y, details="SALISBURY", out="3.00", bal="3,208.74"); y -= 14
    row(y, "12 Oct 26", "BP", "A FRIEND"); y -= 12
    row(y, details="DINNER", out="34.00", bal="3,174.74"); y -= 14
    row(y, "28 Oct 26", None, "Balance carried forward", bal="3,174.74"); y -= 30
    c.setFont("Helvetica", 8)
    c.drawString(60, y, "Credit interest rates  balance  AER  Overdraft interest  balance  EAR"); y -= 12
    c.drawString(60, y, "Credit interest is not paid  over £0  0.00%"); y -= 12
    c.drawString(60, y, "Arranged  over £0  39.90%"); y -= 12
    c.drawString(60, 40, "Example Bank is a division of Example Bank UK plc"); c.showPage()
    tc_page(c, 3, 3)
    c.save()

current_f()
