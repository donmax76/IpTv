using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using HiddenAudioService;
using AudioRecorder;
using AudioRecorder.Shared;

const string PipeName = "AudioRecorderHiddenService";

var jsonOptions = new JsonSerializerOptions
{
    PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    WriteIndented = false
};

AppLogger.Initialize();
AppLogger.Log("HiddenAudioService started");

Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    AppLogger.Log("HiddenAudioService: Ctrl+C pressed, shutting down...");
    Environment.Exit(0);
};

using var controller = new RecordingController();

PipeSecurity CreatePipeSecurity()
{
    var security = new PipeSecurity();

    // Allow full control to the current user
    var currentUser = WindowsIdentity.GetCurrent().User;
    if (currentUser != null)
    {
        security.AddAccessRule(new PipeAccessRule(
            currentUser,
            PipeAccessRights.FullControl,
            AccessControlType.Allow));
    }

    // Allow authenticated users to connect
    security.AddAccessRule(new PipeAccessRule(
        new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
        PipeAccessRights.ReadWrite | PipeAccessRights.CreateNewInstance,
        AccessControlType.Allow));

    return security;
}

while (true)
{
    try
    {
        var pipeSecurity = CreatePipeSecurity();
        using var pipe = new NamedPipeServerStream(
            PipeName,
            PipeDirection.InOut,
            1,
            PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous,
            0,
            0,
            pipeSecurity);

        AppLogger.Log("HiddenAudioService: Waiting for client connection...");
        await pipe.WaitForConnectionAsync();
        AppLogger.Log("HiddenAudioService: Client connected");

        using var reader = new StreamReader(pipe, Encoding.UTF8, leaveOpen: true);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false), leaveOpen: true)
        {
            AutoFlush = true
        };

        string? line;
        while ((line = await reader.ReadLineAsync()) != null)
        {
            if (string.IsNullOrWhiteSpace(line))
            {
                continue;
            }

            ServiceCommand? command = null;
            try
            {
                command = JsonSerializer.Deserialize<ServiceCommand>(line, jsonOptions);
            }
            catch (Exception ex)
            {
                AppLogger.Log("HiddenAudioService: Failed to deserialize command", ex);
                await writer.WriteLineAsync(JsonSerializer.Serialize(
                    ServiceResponse.Fail("Неверный формат команды"), jsonOptions));
                continue;
            }

            var response = controller.Handle(command ?? new ServiceCommand());
            var responseJson = JsonSerializer.Serialize(response, jsonOptions);
            await writer.WriteLineAsync(responseJson);
        }

        AppLogger.Log("HiddenAudioService: Client disconnected");
    }
    catch (Exception ex)
    {
        AppLogger.Log("HiddenAudioService: Pipe loop error", ex);
        await Task.Delay(1000);
    }
}
