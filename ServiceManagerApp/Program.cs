using System;
using System.Security.Principal;
using System.Windows.Forms;

namespace ServiceManagerApp
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            // Require administrator rights to run the manager UI
            if (!IsRunningAsAdministrator())
            {
                MessageBox.Show(
                    "ServiceManagerApp must be run as Administrator.\n" +
                    "Right-click the application and select \"Run as administrator\".",
                    "Administrator required",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                return;
            }

            Application.EnableVisualStyles();
            Application.SetHighDpiMode(HighDpiMode.SystemAware);
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }

        private static bool IsRunningAsAdministrator()
        {
            try
            {
                using var identity = WindowsIdentity.GetCurrent();
                var principal = new WindowsPrincipal(identity);
                return principal.IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch
            {
                return false;
            }
        }
    }
}

