import { useState, useEffect } from 'react';
import axios from 'axios';
import './index.css'; 
import './App.css';   

// Formats JSON strings into readable, indented blocks
const formatJSON = (val) => {
  try {
    if (!val) return '';
    const parsed = JSON.parse(val);
    return JSON.stringify(parsed, null, 2);
  } catch (error) { 
    console.debug("JSON formatting skipped (Plain text detected):", error.message);
    return val; 
  }
};

const extractMethod = (id) => {
  if (!id) return 'GET';
  const method = id.split('_')[0].toUpperCase();
  if (['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) return method;
  return 'GET';
};

function App() {
  const [formData, setFormData] = useState({ 
    clientId: '', 
    clientSecret: '', 
    tenantName: '', 
    instanceUrl: '', 
    requestorEmail: '' 
  });
  
  const [availableApis, setAvailableApis] = useState([]);
  const [selectedApiIds, setSelectedApiIds] = useState([]);
  const [searchTerm, setSearchTerm] = useState('');
  
  const [isSyncing, setIsSyncing] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [expandedApiId, setExpandedApiId] = useState(null);

  useEffect(() => {
    const loadInitialApis = async () => {
      try {
        const response = await axios.get('http://localhost:8080/api/v1/generator/templates');
        setAvailableApis(response.data);
      } catch (error) { 
        console.error("Failed to load APIs:", error); 
      }
    };
    loadInitialApis();
  }, []);

  const handleInputChange = (event) => setFormData({ ...formData, [event.target.name]: event.target.value });

  const handleSync = async () => {
    setIsSyncing(true);
    try {
      const url = "https://cpai-productapi-stg.azurewebsites.net/swagger/v1/swagger.json";
      await axios.post(`http://localhost:8080/api/v1/generator/sync?url=${url}`);
      const response = await axios.get('http://localhost:8080/api/v1/generator/templates');
      setAvailableApis(response.data);
      alert("Database Synced! Mock JSON Payloads generated.");
    } catch (error) { 
      console.error("Sync Error:", error); 
      alert("Sync failed. Check backend server logs."); 
    } finally { 
      setIsSyncing(false); 
    }
  };

  const handleCheckboxChange = (apiId) => {
    setSelectedApiIds((prev) => prev.includes(apiId) ? prev.filter(id => id !== apiId) : [...prev, apiId]);
  };

  const handleGroupToggle = (apisInGroup) => {
    const groupApiIds = apisInGroup.map(api => api.id);
    const areAllSelected = groupApiIds.every(id => selectedApiIds.includes(id));
    
    if (areAllSelected) {
      setSelectedApiIds(prev => prev.filter(id => !groupApiIds.includes(id)));
    } else {
      setSelectedApiIds(prev => [...new Set([...prev, ...groupApiIds])]);
    }
  };

  const toggleDetails = (apiId) => {
    setExpandedApiId(expandedApiId === apiId ? null : apiId);
  };

  // --- Copy as cURL Logic ---
  const handleCopyCurl = (api, method) => {
    const baseUrl = formData.instanceUrl || "https://leah.com";
    let curl = `curl -X '${method}' \\\n  '${baseUrl}${api.apiName.replace('{tenantName}', formData.tenantName || '{tenantName}')}' \\\n`;
    curl += `  -H 'Authorization: Bearer YOUR_TOKEN' \\\n`;
    curl += `  -H 'Content-Type: application/json'`;

    if (api.sampleRequest && method !== 'GET') {
      const cleanBody = api.sampleRequest.replace(/'/g, "'\\''"); 
      curl += ` \\\n  -d '${cleanBody}'`;
    }

    navigator.clipboard.writeText(curl);
    alert("cURL copied to clipboard!");
  };

  // --- Helper function to trigger file downloads ---
  const downloadFile = (filename, content) => {
    const url = window.URL.createObjectURL(new Blob([content], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', filename);
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  };

  const handleGenerate = async () => {
    if (selectedApiIds.length === 0) return alert("Please select at least one API to generate.");
    setIsLoading(true);

    try {
      const response = await axios.post('http://localhost:8080/api/v1/generator/download', {
        ...formData, selectedApiIds
      }, { responseType: 'blob' });

      const textData = await response.data.text();
      let parsedJson = JSON.parse(textData);

      // ====================================================================
      // 🚨 THE DEFINITIVE POSTMAN TRIPWIRE 🚨
      // Forces Postman to render something, even if Java fails.
      // ====================================================================
      if (parsedJson.item) {
        parsedJson.item.forEach(item => {
          if (item.request && item.request.body && item.request.body.raw !== undefined) {
            let rawData = item.request.body.raw;

            // TRIPWIRE: Did Java send us absolutely nothing or an empty object?
            if (!rawData || rawData === '{}' || (typeof rawData === 'object' && Object.keys(rawData).length === 0)) {
                item.request.body.raw = "{\n  \"DEBUG_MESSAGE\": \"The Swagger file has a broken $ref link. Java could not find the schema!\"\n}";
            } 
            // If Java sent an object, force it into a String format
            else if (typeof rawData === 'object') {
              item.request.body.raw = JSON.stringify(rawData, null, 2);
            } 
            // If Java sent a messy string, beautify it
            else if (typeof rawData === 'string') {
              try {
                const parsedString = JSON.parse(rawData);
                item.request.body.raw = JSON.stringify(parsedString, null, 2);
              } catch (e) {
                item.request.body.raw = rawData; // Leave it alone if it won't parse
              }
            }
            
            // Force Postman syntax highlighting
            item.request.body.options = { raw: { language: "json" } };
          }
        });
      }
      // ====================================================================

      const prettyCollection = JSON.stringify(parsedJson, null, 2);
      downloadFile(`Leah_${formData.tenantName || 'Collection'}.json`, prettyCollection);

      // Environment Download
      const environmentJson = {
        name: `Leah_${formData.tenantName || 'Env'}_Environment`,
        values: [
          { key: "base_url", value: formData.instanceUrl || "https://leah.com", type: "default", enabled: true },
          { key: "client_id", value: formData.clientId, type: "default", enabled: true },
          { key: "client_secret", value: formData.clientSecret, type: "default", enabled: true },
          { key: "tenant_name", value: formData.tenantName, type: "default", enabled: true },
          { key: "access_token", value: "", type: "default", enabled: true }
        ],
        _postman_variable_scope: "environment"
      };
      
      const prettyEnv = JSON.stringify(environmentJson, null, 2);
      downloadFile(`Leah_${formData.tenantName || 'Env'}_Environment.json`, prettyEnv);

    } catch (error) { 
      console.error("Generate Error:", error);
      alert("Failed to generate API collection."); 
    } finally { 
      setIsLoading(false); 
    }
  };

  const filteredApis = availableApis.filter(api => 
    (api.apiName && api.apiName.toLowerCase().includes(searchTerm.toLowerCase())) || 
    (api.integrationType && api.integrationType.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  const groupedApis = filteredApis.reduce((acc, api) => {
    const group = api.integrationType || 'General';
    if (!acc[group]) acc[group] = [];
    acc[group].push(api);
    return acc;
  }, {});

  return (
    <div className="container" style={{ paddingBottom: '100px' }}>
      
      <div className="top-panel">
        <div className="header-row">
          <div>
            <h1>Leah API Configurator</h1>
            <div className="subtitle">Enter client details to authorize the Postman Collection.</div>
          </div>
          <button className="btn-sync" type="button" onClick={handleSync} disabled={isSyncing}>
            {isSyncing ? 'Syncing...' : 'SYNC APIS'}
          </button>
        </div>

        <div className="form-grid">
          <div className="form-group"><label>Client ID</label><input className="form-input" type="text" name="clientId" onChange={handleInputChange} /></div>
          <div className="form-group"><label>Client Secret</label><input className="form-input" type="password" name="clientSecret" onChange={handleInputChange} /></div>
          <div className="form-group"><label>Tenant Name</label><input className="form-input" type="text" name="tenantName" onChange={handleInputChange} /></div>
          <div className="form-group"><label>Instance URL</label><input className="form-input" type="text" name="instanceUrl" placeholder="e.g. https://leah.com" onChange={handleInputChange} /></div>
          <div className="form-group"><label>Requestor Email</label><input className="form-input" type="email" name="requestorEmail" onChange={handleInputChange} /></div>
        </div>
      </div>

      <input 
        className="swagger-search" 
        type="text" 
        placeholder="Filter by Path, Name, or Group..." 
        value={searchTerm} 
        onChange={event => setSearchTerm(event.target.value)}
      />

      {Object.entries(groupedApis).map(([groupName, apis]) => {
        const areAllSelected = apis.length > 0 && apis.every(api => selectedApiIds.includes(api.id));

        return (
          <div key={groupName} className="swagger-group">
            <h2 className="swagger-group-title">
              <input 
                type="checkbox" 
                className="swagger-checkbox" 
                style={{ transform: 'scale(1.2)' }}
                checked={areAllSelected}
                onChange={() => handleGroupToggle(apis)}
                onClick={(event) => event.stopPropagation()}
              />
              {groupName} Integration
            </h2>
            
            {apis.map(api => {
              const method = extractMethod(api.id);
              const isExpanded = expandedApiId === api.id;
              
              return (
                <div key={api.id} className={`swagger-endpoint swagger-${method.toLowerCase()}`}>
                  
                  <div className="swagger-summary" onClick={() => toggleDetails(api.id)}>
                    <div className="swagger-summary-left">
                      <input 
                        type="checkbox" 
                        className="swagger-checkbox"
                        checked={selectedApiIds.includes(api.id)}
                        onChange={() => handleCheckboxChange(api.id)}
                        onClick={(event) => event.stopPropagation()} // <-- PREVENTS BUBBLING TO PARENT DIV
                      />
                      <span className="swagger-badge">{method}</span>
                      <span className="swagger-path">{api.apiName}</span>
                    </div>
                    <div style={{ paddingRight: '15px', color: '#3b4151', fontSize: '18px', fontWeight: 'bold' }}>
                      {isExpanded ? '−' : '+'}
                    </div>
                  </div>

                  {isExpanded && (
                    <div className="swagger-expanded">
                      <div className="swagger-section-header">
                        Sample Request Body
                        <button className="btn-curl" onClick={() => handleCopyCurl(api, method)}>
                          📋 Copy cURL
                        </button>
                      </div>
                      <pre className="swagger-code-block">{formatJSON(api.sampleRequest || 'No body required')}</pre>

                      <div className="swagger-section-header">Responses (200 OK)</div>
                      <pre className="swagger-code-block">{formatJSON(api.sampleResponse || 'No response specified')}</pre>
                    </div>
                  )}
                  
                </div>
              );
            })}
          </div>
        );
      })}

      <div className="action-footer">
        <button className="btn-generate" onClick={handleGenerate} disabled={isLoading || selectedApiIds.length === 0}>
          {isLoading ? 'Generating Postman Assets...' : `Generate Collection & Environment (${selectedApiIds.length} Selected)`}
        </button>
      </div>

    </div>
  );
}

export default App;