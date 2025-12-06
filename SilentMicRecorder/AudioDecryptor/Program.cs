using System;
using System.IO;
using System.Security.Cryptography;

namespace AudioDecryptor
{
    internal class Program
    {
        // Те же ключ и IV, что и в сервисе AudioCore.Worker
        private static readonly byte[] EncryptionKey = new byte[32]
        {
            0x3A, 0x7F, 0x21, 0x94, 0xC5, 0xD2, 0x6B, 0x11,
            0x8E, 0x4C, 0xF9, 0x53, 0x07, 0xB8, 0xDA, 0x62,
            0x19, 0xAF, 0x33, 0xE4, 0x5D, 0x70, 0x88, 0x9B,
            0xC1, 0x2E, 0x47, 0x6A, 0x8D, 0x90, 0xAB, 0xCD
        };

        private static readonly byte[] EncryptionIV = new byte[16]
        {
            0x12, 0x34, 0x56, 0x78,
            0x9A, 0xBC, 0xDE, 0xF0,
            0x0F, 0x1E, 0x2D, 0x3C,
            0x4B, 0x5A, 0x69, 0x78
        };

        private static int Main(string[] args)
        {
            Console.OutputEncoding = System.Text.Encoding.UTF8;

            if (args.Length == 0)
            {
                Console.WriteLine("Usage:");
                Console.WriteLine("  AudioDecryptor <path-to-dat>");
                Console.WriteLine();
                Console.WriteLine("Пример:");
                Console.WriteLine("  AudioDecryptor \"D:\\\\SilentRecord\\\\record_20250127_120000_44k_16bit_2ch_60sec.dat\"");
                return 1;
            }

            string inputPath = args[0];

            try
            {
                inputPath = Path.GetFullPath(inputPath);
            }
            catch
            {
                Console.WriteLine("Ошибка: некорректный путь к файлу.");
                return 1;
            }

            if (!File.Exists(inputPath))
            {
                Console.WriteLine($"Ошибка: файл не найден: {inputPath}");
                return 1;
            }

            string inputFileName = Path.GetFileName(inputPath);
            string inputExt = Path.GetExtension(inputPath);
            
            // Если файл уже имеет расширение .dat - расшифровываем напрямую
            bool isDirectDatFile = inputExt.Equals(".dat", StringComparison.OrdinalIgnoreCase);
            
            string? decryptedFileName = null;
            string renamedPath = inputPath;
            
            if (!isDirectDatFile)
            {
                // Шаг 1: Расшифровываем имя файла
                decryptedFileName = DecryptFileName(inputFileName);
                
                if (decryptedFileName == null)
                {
                    Console.WriteLine("Ошибка: Не удалось расшифровать имя файла.");
                    return 2;
                }

                // Шаг 2: Переименовываем файл в расшифрованное имя
                renamedPath = Path.Combine(Path.GetDirectoryName(inputPath) ?? "", decryptedFileName);
                Console.WriteLine($"Расшифрованное имя файла: {decryptedFileName}");
                
                if (File.Exists(renamedPath) && !inputPath.Equals(renamedPath, StringComparison.OrdinalIgnoreCase))
                {
                    File.Delete(renamedPath);
                }
                File.Move(inputPath, renamedPath);
                Console.WriteLine($"Файл переименован: {inputFileName} -> {decryptedFileName}");
            }
            else
            {
                // Файл уже .dat - используем его как есть
                Console.WriteLine($"Обнаружен .dat файл: {inputFileName}");
                decryptedFileName = inputFileName;
            }

            // Шаг 3: Проверяем расширение в переименованном файле
            string ext = Path.GetExtension(renamedPath);
            bool isContentEncrypted = ext.Equals(".dat", StringComparison.OrdinalIgnoreCase);
            
            try
            {
                if (isContentEncrypted)
                {
                    // Расширение .dat - расшифровываем содержимое файла
                    // Заменяем .dat на .mp3 для выходного файла
                    string finalOutputPath = Path.ChangeExtension(renamedPath, ".mp3");
                    
                    Console.WriteLine("========================================");
                    Console.WriteLine("Расшифровка содержимого файла...");
                    Console.WriteLine($"Входной файл: {renamedPath}");
                    Console.WriteLine($"Выходной файл: {finalOutputPath}");
                    Console.WriteLine("========================================");
                    
                    // Удаляем выходной файл, если он существует
                    if (File.Exists(finalOutputPath))
                    {
                        File.Delete(finalOutputPath);
                    }
                    
                    // Расшифровываем содержимое
                    DecryptFile(renamedPath, finalOutputPath);
                    
                    // Удаляем исходный .dat файл после успешной расшифровки
                    try
                    {
                        File.Delete(renamedPath);
                        Console.WriteLine($"Исходный .dat файл удалён: {Path.GetFileName(renamedPath)}");
                    }
                    catch (Exception delEx)
                    {
                        Console.WriteLine($"Не удалось удалить исходный .dat файл: {delEx.Message}");
                    }
                    
                    Console.WriteLine($"Расшифрованный файл создан: {Path.GetFileName(finalOutputPath)}");
                    
                    Console.WriteLine("========================================");
                    Console.WriteLine("Расшифровка завершена успешно.");
                    Console.WriteLine($"Выходной файл : {finalOutputPath}");
                    Console.WriteLine("========================================");
                }
                else
                {
                    // Расширение .mp3 или .wav - файл уже переименован, ничего не делаем
                    Console.WriteLine("========================================");
                    Console.WriteLine("Файл переименован успешно.");
                    Console.WriteLine($"Выходной файл : {renamedPath}");
                    Console.WriteLine("========================================");
                }
                return 0;
            }
            catch (Exception ex)
            {
                Console.WriteLine("Ошибка при расшифровке файла:");
                Console.WriteLine(ex.Message);
                return 2;
            }
        }

        /// <summary>
        /// Расшифровывает имя файла из зашифрованного Base64 строки
        /// </summary>
        private static string? DecryptFileName(string encryptedFileName)
        {
            try
            {
                // Восстанавливаем Base64 формат
                string base64 = encryptedFileName.Replace('_', '/').Replace('-', '+');
                // Добавляем padding если нужно
                int mod4 = base64.Length % 4;
                if (mod4 != 0)
                    base64 += new string('=', 4 - mod4);

                byte[] encrypted = Convert.FromBase64String(base64);

                using var aes = Aes.Create();
                if (aes == null)
                    return null;

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                using var decryptor = aes.CreateDecryptor();
                byte[] decryptedName = decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
                return System.Text.Encoding.UTF8.GetString(decryptedName);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Читает оригинальное имя файла из начала файла (зашифрованное в метаданных)
        /// </summary>
        private static string? ReadOriginalFileName(string inputPath)
        {
            try
            {
                using var aes = Aes.Create();
                if (aes == null)
                    return null;

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                using var input = new FileStream(inputPath, FileMode.Open, FileAccess.Read, FileShare.Read);
                
                // Читаем длину зашифрованного имени (4 байта)
                byte[] lengthBytes = new byte[4];
                int bytesRead = input.Read(lengthBytes, 0, 4);
                if (bytesRead != 4)
                    return null;

                int nameLength = BitConverter.ToInt32(lengthBytes, 0);
                if (nameLength <= 0 || nameLength > 1024) // Защита от некорректных данных
                    return null;

                // Читаем зашифрованное имя
                byte[] encryptedName = new byte[nameLength];
                bytesRead = input.Read(encryptedName, 0, nameLength);
                if (bytesRead != nameLength)
                    return null;

                // Расшифровываем имя
                using var decryptor = aes.CreateDecryptor();
                byte[] decryptedName = decryptor.TransformFinalBlock(encryptedName, 0, encryptedName.Length);
                return System.Text.Encoding.UTF8.GetString(decryptedName);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Ошибка при чтении оригинального имени: {ex.Message}");
                return null;
            }
        }

        private static void DecryptFile(string inputPath, string outputPath)
        {
            try
            {
                using var input = new FileStream(inputPath, FileMode.Open, FileAccess.Read, FileShare.Read);
                using var output = new FileStream(outputPath, FileMode.Create, FileAccess.Write, FileShare.None);
                
                // Расшифровка содержимого файла
                using var aes = Aes.Create();
                if (aes == null)
                    throw new InvalidOperationException("Не удалось создать AES-провайдер.");

                aes.Key = EncryptionKey;
                aes.IV = EncryptionIV;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;

                Console.WriteLine($"Начало расшифровки содержимого...");
                Console.WriteLine($"Размер входного файла: {input.Length} байт");
                
                using var crypto = new CryptoStream(input, aes.CreateDecryptor(), CryptoStreamMode.Read);
                crypto.CopyTo(output);
                
                output.Flush();
                Console.WriteLine($"Расшифровка завершена. Размер выходного файла: {output.Length} байт");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Ошибка при расшифровке содержимого: {ex.GetType().Name}: {ex.Message}");
                Console.WriteLine($"StackTrace: {ex.StackTrace}");
                throw;
            }
        }
    }
}


