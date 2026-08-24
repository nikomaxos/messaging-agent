const express = require('express');
const cors = require('cors');
const axios = require('axios');
const mccMncList = require('mcc-mnc-list');
const mobilePrefixes = require('mobile-prefixes');
const stringSimilarity = require('string-similarity');

const app = express();
app.use(cors());
app.use(express.json());

const CORE_SERVICE_URL = process.env.CORE_SERVICE_URL || 'http://localhost:8082';

app.post('/api/prefixes/sync', async (req, res) => {
    try {
        console.log('Fetching latest MCC/MNC list...');
        const allRecords = mccMncList.all();
        
        // Get all mobile prefixes globally (it returns an array if we just require the json or use a method)
        // Wait, mobile-prefixes might not have a getAll() method. Let's just use it dynamically per country.
        
        // Build tree: Country -> Networks
        const countryMap = new Map();
        for (const r of allRecords) {
            if (!r.mcc || !r.mnc || !r.countryCode) continue;
            const countryName = r.countryName || 'Unknown';
            const iso = r.countryCode.substring(0, 5); // limit length just in case
            
            if (!countryMap.has(iso)) {
                countryMap.set(iso, { name: countryName, isoCode: iso, mccs: new Set(), networkMap: new Map() });
            }
            const countryObj = countryMap.get(iso);
            countryObj.mccs.add(r.mcc);

            const netName = r.brand || r.operator || 'Unknown';
            if (!countryObj.networkMap.has(netName)) {
                countryObj.networkMap.set(netName, { name: netName, mncs: new Set(), rawPrefixes: new Set() });
            }
            countryObj.networkMap.get(netName).mncs.add(r.mnc);
        }

        // Now merge prefixes
        for (const c of countryMap.values()) {
            try {
                // Try to get prefixes for this country
                const prefixes = mobilePrefixes.byCountryName(c.name) || [];
                if (prefixes.length > 0) {
                    const availableCarriers = [...new Set(prefixes.map(p => p.carrierName))];
                    if (availableCarriers.length > 0) {
                        for (const n of c.networkMap.values()) {
                            // Find best match for network name among available carriers
                            const match = stringSimilarity.findBestMatch(n.name, availableCarriers);
                            if (match.bestMatch.rating > 0.4) {
                                // Match found! Get all prefixes for this carrier
                                const matchedCarrier = match.bestMatch.target;
                                prefixes.filter(p => p.carrierName === matchedCarrier).forEach(p => {
                                    n.rawPrefixes.add(p.fullCode);
                                });
                            }
                        }
                    }
                }
            } catch(e) {
                // mobilePrefixes might throw if country is not found, just skip
            }
        }

        const payload = Array.from(countryMap.values()).map(c => ({
            name: c.name,
            isoCode: c.isoCode,
            mccs: Array.from(c.mccs),
            networks: Array.from(c.networkMap.values()).map(n => ({
                name: n.name,
                mncs: Array.from(n.mncs),
                prefixes: Array.from(n.rawPrefixes)
            }))
        }));

        console.log(`Prepared ${payload.length} records. Sending to core-service...`);
        const response = await axios.post(`${CORE_SERVICE_URL}/api/routing/prefixes/bulk`, payload, {
            headers: {
                Authorization: req.headers.authorization
            }
        });
        
        res.json({ success: true, count: response.data });
    } catch (err) {
        console.error('Error syncing prefixes:', err.message);
        res.status(500).json({ error: err.message });
    }
});

const PORT = process.env.PORT || 8085;
app.listen(PORT, () => {
    console.log(`prefix-updater microservice listening on port ${PORT}`);
});
