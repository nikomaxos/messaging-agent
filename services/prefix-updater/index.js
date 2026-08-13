const express = require('express');
const cors = require('cors');
const axios = require('axios');
const mccMncList = require('mcc-mnc-list');

const app = express();
app.use(cors());
app.use(express.json());

const CORE_SERVICE_URL = process.env.CORE_SERVICE_URL || 'http://localhost:8082';

app.post('/api/prefixes/sync', async (req, res) => {
    try {
        console.log('Fetching latest MCC/MNC list...');
        const allRecords = mccMncList.all();
        
        // Map to CountryPrefix java object format
        const payload = allRecords.map(r => ({
            countryName: r.countryName || 'Unknown',
            prefix: '', // The npm module mcc-mnc-list does not provide dialing prefixes out of the box, we leave empty
            networkName: r.brand || r.operator || 'Unknown',
            mcc: r.mcc,
            mnc: r.mnc,
            iso: r.countryCode,
            active: true
        })).filter(r => r.mcc && r.mnc && r.iso); // Ensure essential fields

        console.log(`Prepared ${payload.length} records. Sending to core-service...`);
        const response = await axios.post(`${CORE_SERVICE_URL}/api/routing/prefixes/bulk`, payload);
        
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
