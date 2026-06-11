let trainingBuffer = [];
let currentLineIndex = 0;

function resetTrainingUI() {
    trainingBuffer = [];
    currentLineIndex = 0;
    document.getElementById("trainingProgress").style.display = "none";
    alert("Progresso de treino resetado.");
}

function prepareBuffer() {
    const text = document.getElementById("trainingArea").value;
    const lines = text.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    
    if (trainingBuffer.length === 0 || text !== trainingBuffer.raw) {
        trainingBuffer = lines.map(l => l.split(',').map(e => e.trim()));
        trainingBuffer.raw = text;
        currentLineIndex = 0;
    }
}

function runInstantTraining() {
    prepareBuffer();
    const jsonBatch = JSON.stringify(trainingBuffer);
    const responseJson = RTA.trainBatch(jsonBatch); 
    
    handleTrainingResponse(responseJson, "Treino completo finalizado.");
    currentLineIndex = trainingBuffer.length;
    updateTrainingStatus();
}

function runNextLineTraining() {
    prepareBuffer();
    
    if (currentLineIndex >= trainingBuffer.length) {
        alert("Fim da lista de treino. Clique em 'Resetar' para recomeçar.");
        return;
    }

    const currentLine = trainingBuffer[currentLineIndex];
    const jsonSession = JSON.stringify(currentLine);
    
    const responseJson = RTA.trainSingleSession(jsonSession);
    
    handleTrainingResponse(responseJson, `Linha ${currentLineIndex + 1} processada: ${currentLine.join(' -> ')}`);
    
    currentLineIndex++;
    updateTrainingStatus();
}

function handleTrainingResponse(json, msg) {
    const data = JSON.parse(json);
    if (data.error) {
        alert(data.error);
    } else {
        updateAllViews(json);
        console.log(msg);
    }
}

function updateTrainingStatus() {
    const prog = document.getElementById("trainingProgress");
    prog.style.display = "block";
    document.getElementById("currentSessionIdx").innerText = currentLineIndex;
    document.getElementById("totalSessionsIdx").innerText = trainingBuffer.length;
}

function syncTrainedWeightsToEditor() {
    const currentFullCode = editor.getValue();
    const nameMatch = currentFullCode.match(/^\s*name\s+[a-zA-Z0-9_]+[;\s]*/m);
    const nameLine = nameMatch ? nameMatch[0].trim() : "";

    let updatedCode = RTA.getUpdatedSource();
    
    if (updatedCode) {

        if (nameLine !== "") {
            if (!updatedCode.trim().startsWith("name")) {
                updatedCode = nameLine + "\n" + updatedCode;
            }
        }

        editor.setValue(updatedCode);
        

        loadAndRender(); 
        
        alert("Pesos sincronizados");
    }
}



function handleTrainingFileUpload(event) {
    const file = event.target.files[0];
    if (!file) return;

    document.getElementById("trainingProgress").style.display = "block";
    document.getElementById("currentSessionIdx").innerText = "0";
    
    const fileSizeMB = (file.size / 1024 / 1024).toFixed(2);
    document.getElementById("totalSessionsIdx").innerText = `Calculando... (Total: ${fileSizeMB} MB)`;

    const CHUNK_SIZE = 250 * 1024; 
    let offset = 0;
    let remainder = ""; 
    let totalLinesProcessed = 0;
    
    const MAX_LINES = 500000;

    function readNextChunk() {
        if (offset >= file.size) {
            if (remainder.trim().length > 0 && totalLinesProcessed < MAX_LINES) {
                const responseJson = RTA.trainMassiveRaw(remainder);
                updateAllViews(responseJson);
                totalLinesProcessed++;
            }
            
            document.getElementById("currentSessionIdx").innerText = totalLinesProcessed.toLocaleString();
            document.getElementById("totalSessionsIdx").innerText = totalLinesProcessed.toLocaleString() + " (Finalizado!)";
            alert(`Treino Massivo concluído! Foram lidas ${totalLinesProcessed.toLocaleString()} linhas.`);
            return;
        }

        const slice = file.slice(offset, offset + CHUNK_SIZE);
        const reader = new FileReader();

        reader.onload = function(e) {
            const textChunk = remainder + e.target.result;
            const lines = textChunk.split('\n');

            remainder = lines.pop();

            let validLines = lines.filter(l => l.trim().length > 0);
            
            let reachedLimit = false;
            if (totalLinesProcessed + validLines.length >= MAX_LINES) {
                const allowed = MAX_LINES - totalLinesProcessed;
                validLines = validLines.slice(0, allowed); 
                reachedLimit = true;
            }

            totalLinesProcessed += validLines.length;

            if (validLines.length > 0) {
                const chunkStr = validLines.join('\n');
                
                const responseJson = RTA.trainMassiveRaw(chunkStr);
                const data = JSON.parse(responseJson);
                
                if (data.error) {
                    alert(`Erro no treino (linha aprox. ${totalLinesProcessed}): ${data.error}`);
                    return;
                }

                if (reachedLimit || (offset + CHUNK_SIZE >= file.size && remainder.trim().length === 0)) {
                    updateAllViews(responseJson);
                }
            }

            const readMB = ((offset + CHUNK_SIZE) / 1024 / 1024).toFixed(2);
            document.getElementById("currentSessionIdx").innerText = `${totalLinesProcessed.toLocaleString()} linhas (${readMB} MB)`;
            
            if (reachedLimit) {
                document.getElementById("totalSessionsIdx").innerText = MAX_LINES.toLocaleString() + " (Limite Atingido!)";
                alert(`Treino da versão Web concluído! O limite de ${MAX_LINES.toLocaleString()} linhas foi atingido com sucesso.`);
                return; 
            }

            offset += CHUNK_SIZE;
            setTimeout(readNextChunk, 15);
        };

        reader.readAsText(slice);
    }

    readNextChunk();
    
    event.target.value = '';
}
function processTrainingInChunks(lines, currentIndex, chunkSize) {
    if (currentIndex >= lines.length) return;

    const chunkStr = lines.slice(currentIndex, currentIndex + chunkSize).join('\n');
    
    const responseJson = RTA.trainMassiveRaw(chunkStr);
    const data = JSON.parse(responseJson);
    
    if (data.error) {
        alert("Erro no treino perto da linha " + currentIndex + ": " + data.error);
        return;
    }

    currentIndex += chunkSize;
    if (currentIndex > lines.length) currentIndex = lines.length;
    
    document.getElementById("currentSessionIdx").innerText = currentIndex;

    if (currentIndex >= lines.length) {
        updateAllViews(responseJson);
        alert("Treino de " + lines.length.toLocaleString() + " sessões concluído com extrema rapidez!");
    } else {
        setTimeout(() => processTrainingInChunks(lines, currentIndex, chunkSize), 10);
    }
}