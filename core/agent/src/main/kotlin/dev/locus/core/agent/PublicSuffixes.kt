package dev.locus.core.agent

/**
 * Names under which unrelated people get their own sites, so a host named by the user is never read as
 * covering "everything below it" (docs/design/taint.md, "Hosts you named"). Naming `github.com` trusts
 * `docs.github.com`; naming `github.io` or `co.uk` trusts only that exact name, because
 * `attacker.github.io` belongs to someone else.
 *
 * This is a compact snapshot of the Public Suffix List (publicsuffix.org), not the whole list: the
 * multi-label suffixes of the larger country domains, and the "private" section's hosting and tunnelling
 * names that a person could plausibly type (static hosts, serverless platforms, tunnels, dynamic DNS,
 * object storage). A name missing here is treated as a normal registrable domain, the old behaviour; a few
 * extras (`amazonaws.com`, `cloudfront.net`) are listed on purpose although the list names only subdomains of them.
 * Every name with no dot ("com", "uk") counts as a suffix.
 */
object PublicSuffixes {
    /** Whether [host] (lower-case, no trailing dot) is a public suffix, i.e. not a site one party runs. */
    fun isPublicSuffix(host: String): Boolean = '.' !in host || host in SUFFIXES

    private val ICANN = """
        co.uk org.uk me.uk ltd.uk plc.uk net.uk sch.uk ac.uk gov.uk nhs.uk police.uk
        com.au net.au org.au edu.au gov.au asn.au id.au
        co.jp ne.jp or.jp ac.jp go.jp ad.jp ed.jp gr.jp lg.jp
        co.nz org.nz net.nz ac.nz govt.nz school.nz geek.nz
        co.za org.za net.za ac.za gov.za web.za
        com.br net.br org.br gov.br edu.br
        com.cn net.cn org.cn gov.cn edu.cn ac.cn
        com.mx org.mx gob.mx edu.mx net.mx
        com.ar org.ar gob.ar net.ar edu.ar
        com.tr org.tr net.tr gov.tr edu.tr
        com.tw org.tw net.tw edu.tw gov.tw idv.tw
        com.hk org.hk net.hk edu.hk gov.hk idv.hk
        com.sg org.sg net.sg edu.sg gov.sg per.sg
        co.in net.in org.in ac.in gov.in res.in firm.in gen.in ind.in
        co.kr or.kr ne.kr go.kr re.kr ac.kr
        co.id or.id web.id ac.id go.id my.id sch.id
        co.il org.il net.il ac.il gov.il muni.il
        com.ua org.ua net.ua gov.ua edu.ua in.ua
        com.pl net.pl org.pl edu.pl gov.pl info.pl biz.pl waw.pl
        com.my org.my net.my edu.my gov.my
        com.ph org.ph net.ph edu.ph gov.ph
        com.vn net.vn org.vn edu.vn gov.vn
        com.pk org.pk net.pk edu.pk gov.pk
        com.eg org.eg net.eg edu.eg gov.eg
        com.sa org.sa net.sa edu.sa gov.sa
        com.ng org.ng net.ng edu.ng gov.ng
        co.ke or.ke ne.ke ac.ke go.ke
        co.th or.th in.th ac.th go.th
        com.co org.co net.co edu.co gov.co
        com.pe org.pe net.pe edu.pe gob.pe
        com.ve org.ve net.ve
        com.uy org.uy net.uy
        com.ec org.ec net.ec
        com.cy org.cy net.cy
        com.gr org.gr net.gr
        com.pt org.pt
        com.es org.es nom.es
        co.at or.at ac.at gv.at
        co.hu org.hu
        com.ru org.ru net.ru
        co.uz com.uz
        com.bd org.bd net.bd edu.bd
        com.lk org.lk
        com.np org.np
        co.ug or.ug
        co.tz or.tz
        co.zw
        co.zm
        com.gh org.gh
        co.ma
        com.dz
        com.tn
        com.lb
        com.qa
        com.kw
        com.bh
        com.om
        com.jo
        co.ae
        com.sv com.gt com.pa com.do com.ni com.hn com.bo com.py com.cu
    """

    /** The Public Suffix List's private section: hosting, tunnelling and dynamic-DNS names anyone can get a subdomain of. */
    private val HOSTING = """
        github.io githubusercontent.com githubpreview.dev gitlab.io bitbucket.io
        vercel.app vercel.dev now.sh vusercontent.net
        netlify.app netlify.live pages.dev workers.dev r2.dev
        web.app firebaseapp.com firebaseio.com appspot.com run.app cloudfunctions.net blogspot.com
        herokuapp.com herokussl.com onrender.com fly.dev railway.app up.railway.app deno.dev deno.net val.run
        glitch.me repl.co replit.dev replit.app csb.app gitpod.io
        surge.sh readthedocs.io pythonanywhere.com
        azurewebsites.net azurestaticapps.net azureedge.net blob.core.windows.net cloudapp.net trafficmanager.net
        amazonaws.com s3.amazonaws.com compute.amazonaws.com compute-1.amazonaws.com cloudfront.net
        elasticbeanstalk.com amplifyapp.com awsapprunner.com
        ngrok.io ngrok.app ngrok.dev ngrok-free.app ngrok-free.dev trycloudflare.com loca.lt localtunnel.me serveo.net
        telebit.io pinggy.link pagekite.me
        duckdns.org no-ip.org no-ip.com no-ip.biz ddns.net dyndns.org dyndns.biz dyndns.info hopto.org myftp.org zapto.org freeddns.org
        000webhostapp.com wixsite.com weebly.com webflow.io myshopify.com carrd.co notion.site hashnode.dev bubbleapps.io framer.website
        eu.org rhcloud.com cloudns.net
    """

    private val SUFFIXES: Set<String> = (ICANN + " " + HOSTING).split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
}
